package com.pearson.statspoller.internal_metric_collectors.mongo;

import com.mongodb.BasicDBObject;
import java.util.ArrayList;
import java.util.List;
import com.pearson.statspoller.internal_metric_collectors.InternalCollectorFramework;
import com.pearson.statspoller.metric_formats.graphite.GraphiteMetric;
import com.pearson.statspoller.utilities.StackTrace;
import com.pearson.statspoller.utilities.Threads;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import com.mongodb.MongoClient;
import com.mongodb.MongoClientURI;
import com.mongodb.client.MongoCollection;
import com.mongodb.client.MongoCursor;
import com.mongodb.client.MongoDatabase;
import com.mongodb.client.MongoIterable;
import java.math.BigDecimal;
import java.text.ParseException;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Map.Entry;
import org.apache.commons.lang.StringUtils;
import org.boon.Pair;
import org.bson.BsonTimestamp;
import org.bson.Document;

/**
 * @author Jeffrey Schmidt
 * @author Judah Walker
 */
public class MongoMetricCollector extends InternalCollectorFramework implements Runnable {

    private static final Logger logger = LoggerFactory.getLogger(MongoMetricCollector.class.getName());

    private final String host_;
    private final int port_;
    private final String username_;
    private final String password_;
    private final boolean mongoVerboseOutputValue_;

    public MongoMetricCollector(boolean isEnabled, long collectionInterval, String metricPrefix,
            String outputFilePathAndFilename, boolean writeOutputFiles,
            String host, int port, String username, String password, boolean mongoVerboseOutputValue) {
        super(isEnabled, collectionInterval, metricPrefix, outputFilePathAndFilename, writeOutputFiles);
        this.host_ = host;
        this.port_ = port;
        this.username_ = username;
        this.password_ = password;
        this.mongoVerboseOutputValue_ = mongoVerboseOutputValue;
    }

    @Override
    public void run() {

        while (super.isEnabled()) {
            long routineStartTime = System.currentTimeMillis();

            List<GraphiteMetric> graphiteMetrics = getMongoMetrics();

            super.outputGraphiteMetrics(graphiteMetrics);

            long routineTimeElapsed = System.currentTimeMillis() - routineStartTime;

            logger.info("Finished Mongo metric collection routine. MongoServer=" + host_ + ":" + port_
                    + ", MetricsCollected=" + graphiteMetrics.size()
                    + ", MetricCollectionTime=" + routineTimeElapsed);

            long sleepTimeInMs = getCollectionInterval() - routineTimeElapsed;

            if (sleepTimeInMs >= 0) {
                Threads.sleepMilliseconds(sleepTimeInMs);
            }
        }

    }

    private List<GraphiteMetric> getMongoMetrics() {

        List<GraphiteMetric> graphiteMetrics = new ArrayList<>();

        MongoClient mongoClient = null;

        try {
            MongoClientURI uri = new MongoClientURI("mongodb://" + username_ + ":" + password_
                    + "@" + host_ + ":" + port_ + "/?authSource=admin");

            //try blocks do not work with mongoclient as connection process is done by a background thread.
            if ((username_ != null) && !username_.isEmpty()) {
                mongoClient = new MongoClient(uri);
            }
            else {
                try {
                    mongoClient = new MongoClient(host_, port_);
                }
                catch (Exception e) {
                    logger.error(e.toString() + System.lineSeparator() + StackTrace.getStringFromStackTrace(e));
                }
            }

            if (mongoClient == null) {
                graphiteMetrics.add(new GraphiteMetric("Available", BigDecimal.ZERO, ((int) (System.currentTimeMillis() / 1000))));
                return graphiteMetrics;
            }

            MongoDatabase db = mongoClient.getDatabase("admin");

            Document replSetStatus = new Document();
            Document isMaster = new Document();
            
            try {
                isMaster = db.runCommand(new Document().append("isMaster", 1));
                graphiteMetrics.add(new GraphiteMetric("Available", BigDecimal.ONE, ((int) (System.currentTimeMillis() / 1000))));
            }
            catch (Exception e) {
                logger.error(e.toString() + System.lineSeparator() + StackTrace.getStringFromStackTrace(e));
                graphiteMetrics.add(new GraphiteMetric("Available", BigDecimal.ZERO, ((int) (System.currentTimeMillis() / 1000))));
            }

            if (isMaster.containsKey("setName")) {
                try {
                    replSetStatus = db.runCommand(new Document().append("replSetGetStatus", 1));
                }
                catch (Exception e) {
                    logger.error(e.toString() + System.lineSeparator() + StackTrace.getStringFromStackTrace(e));
                }

                if ((replSetStatus != null) && replSetStatus.containsKey("ok") && replSetStatus.get("ok").equals(1.0)) {
                    graphiteMetrics.addAll(processDocumentAndAddToMetrics(replSetStatus, "replSetStatus"));
                    
                    try {
                        Document getReplicationInfo = getReplicationInfo(mongoClient, replSetStatus);
                        if (getReplicationInfo != null) graphiteMetrics.addAll(processDocumentAndAddToMetrics(getReplicationInfo, "replSetStatus"));
                    }
                    catch (Exception e) {
                        logger.error(e.toString() + System.lineSeparator() + StackTrace.getStringFromStackTrace(e));
                    }
                }

            }
            
            Document serverStatus = null;
            
            try {
                serverStatus = db.runCommand(new Document().append("serverStatus", 1));
            }
            catch (Exception e) {
                logger.error(e.toString() + System.lineSeparator() + StackTrace.getStringFromStackTrace(e));
                return graphiteMetrics;
            }

            if ((serverStatus != null) && serverStatus.containsKey("ok") && serverStatus.get("ok").equals(1.0)) {
                
                // if not verbose & is an arbiter, then limit the metric output
                if ((replSetStatus != null) && (replSetStatus.isEmpty() || (replSetStatus.containsKey("myState") && (new BigDecimal(replSetStatus.get("myState").toString()).intValue() != 7)))) {
                   graphiteMetrics.addAll(processDocumentAndAddToMetrics(serverStatus, "serverStatus")); 
                }
                else if (!mongoVerboseOutputValue_) {
                   List<GraphiteMetric> serverStatusGraphiteMetrics = processDocumentAndAddToMetrics(serverStatus, "serverStatus");
                   for (GraphiteMetric graphiteMetric : serverStatusGraphiteMetrics) {
                       if ((graphiteMetric.getMetricPath() != null) && 
                               (graphiteMetric.getMetricPath().startsWith("serverStatus.network") || 
                               graphiteMetric.getMetricPath().startsWith("serverStatus.connections") ||
                               graphiteMetric.getMetricPath().startsWith("serverStatus.uptime") ||
                               graphiteMetric.getMetricPath().startsWith("serverStatus.pid"))) {
                           graphiteMetrics.add(graphiteMetric);
                       }
                   }
                }
                else {
                    graphiteMetrics.addAll(processDocumentAndAddToMetrics(serverStatus, "serverStatus")); 
                }
            }

            Document databasesList = new Document();
            
            // if arbiter, do not run. if not arbiter, collect collection & database metrics
            if ((replSetStatus != null) && (replSetStatus.isEmpty() || (replSetStatus.containsKey("myState") && (new BigDecimal(replSetStatus.get("myState").toString()).intValue() != 7)))) {
                try {
                    databasesList = db.runCommand(new Document().append("listDatabases", 1));
                }
                catch (Exception e) {
                    logger.error(e.toString() + System.lineSeparator() + StackTrace.getStringFromStackTrace(e));
                }

                if (databasesList.containsKey("ok") && databasesList.get("ok").equals(1.0)) {
                    ArrayList<Document> databases;
                    databases = (ArrayList) databasesList.get("databases");

                    for (Document datab : databases) {
                        String dbName = datab.get("name").toString();
                        MongoDatabase currentDatabase = mongoClient.getDatabase(dbName);

                        Document dbStats = currentDatabase.runCommand(new Document().append("dbStats", 1).append("scale", 1));
                        graphiteMetrics.addAll(processDocumentAndAddToMetrics(dbStats, "dbStats." + dbName + "."));

                        MongoIterable collections = currentDatabase.listCollectionNames();
                        MongoCursor iterator = collections.iterator();

                        while (iterator.hasNext()) {
                            String graphiteFriendlyDbName = GraphiteMetric.getGraphiteSanitizedString(dbName, true, true);
                            String collection = iterator.next().toString();
                            Document collStats = currentDatabase.runCommand(new Document().append("collStats", collection).append("scale", 1).append("verbose", true));
                            graphiteMetrics.addAll(processDocumentAndAddToMetrics(collStats, "collectionStats." + graphiteFriendlyDbName + "." + collection));
                        }
                    }
                }
            }
        }
        catch (Exception e) {
            logger.error(e.toString() + System.lineSeparator() + StackTrace.getStringFromStackTrace(e));
        }
        finally {
            try {
                if (mongoClient != null) mongoClient.close();
            }
            catch (Exception e) {
                logger.error(e.toString() + System.lineSeparator() + StackTrace.getStringFromStackTrace(e));
            }

            mongoClient = null;
        }

        return graphiteMetrics;
    }

    private List<GraphiteMetric> processDocumentAndAddToMetrics(Document document, String origin) {

        if ((document == null) || (origin == null)) {
            return new ArrayList<>();
        }

        List<GraphiteMetric> graphiteMetrics = new ArrayList<>();

        try {
            document = filterDocument(document, origin);

            List<Pair<String, BigDecimal>> metrics = new ArrayList<>();
            metrics = buildMetrics(origin, document, metrics);

            int metricTimestamp = ((int) (System.currentTimeMillis() / 1000));

            for (Pair<String, BigDecimal> pair : metrics) {
                String graphiteFriendlyMetricPath = GraphiteMetric.getGraphiteSanitizedString(pair.getFirst(), true, true);
                graphiteMetrics.add(new GraphiteMetric(graphiteFriendlyMetricPath, pair.getSecond(), metricTimestamp));
            }
        }
        catch (Exception e) {
            logger.error(e.toString() + System.lineSeparator() + StackTrace.getStringFromStackTrace(e));
        }

        return graphiteMetrics;
    }

    private Document filterDocument(Document document, String origin) {

        if ((document == null) || document.isEmpty() || (origin == null)) {
            return new Document();
        }

        Document documentCopy = null;

        try {
            documentCopy = Document.parse(document.toJson());

            for (Entry entry : document.entrySet()) {
                String key = entry.getKey().toString();

                if (entry.getValue() instanceof Document) {

                    if (key.equalsIgnoreCase("commands")) { //Ignore commands field.
                        documentCopy.remove(key);
                        continue;
                    }

                    if (!mongoVerboseOutputValue_ && !StringUtils.startsWithIgnoreCase(origin, "serverStatus") && (key.equalsIgnoreCase("indexDetails") || key.equalsIgnoreCase("wiredTiger"))) {
                        documentCopy.remove(key);
                        continue;
                    }

                    Document passdown = (Document) entry.getValue();
                    documentCopy.put(key, filterDocument(passdown, origin));

                    if (((Document) documentCopy.get(key)).isEmpty()) {
                        documentCopy.remove(key);
                    }
                }
                else if ((documentCopy.get(key) instanceof Integer) || (documentCopy.get(key) instanceof Double) || (documentCopy.get(key) instanceof Long)) {
                    if (key.equalsIgnoreCase("ok")) {
                        documentCopy.remove(key);
                    }
                }
                else {
                    documentCopy.remove(key);
                }

            }
        }
        catch (Exception e) {
            logger.error(e.toString() + System.lineSeparator() + StackTrace.getStringFromStackTrace(e));
        }

        return documentCopy;
    }

    private List<Pair<String, BigDecimal>> buildMetrics(String start, Document document, List<Pair<String, BigDecimal>> metrics) {

        if ((start == null) || (document == null) || document.isEmpty()) {
            return new ArrayList<>();
        }

        try {
            Document documentCopy = Document.parse(document.toJson());

            for (Entry entry : document.entrySet()) {
                String key = entry.getKey().toString();

                if ((documentCopy.get(key) instanceof Integer) || (documentCopy.get(key) instanceof Double) || (documentCopy.get(key) instanceof Long)) {
                    if (start.isEmpty()) {
                        metrics.add(new Pair(key, new BigDecimal(documentCopy.get(key).toString())));
                    }
                    else {
                        metrics.add(new Pair(start + "." + key, new BigDecimal(documentCopy.get(key).toString())));
                    }
                }
                else if (entry.getValue() instanceof Document) {
                    Document passdown = (Document) entry.getValue();
                    if (start.isEmpty()) {
                        buildMetrics(key, passdown, metrics);
                    }
                    else {
                        buildMetrics(start + "." + key, passdown, metrics);
                    }
                }
            }
        }
        catch (Exception e) {
            logger.error(e.toString() + System.lineSeparator() + StackTrace.getStringFromStackTrace(e));
        }

        return metrics;
    }

    private Document getReplicationInfo(MongoClient mongoClient, Document replSetStatus) throws ParseException {
        
        if ((mongoClient == null) || (replSetStatus == null)) {
            return null;
        }
        
        MongoDatabase localdb = mongoClient.getDatabase("local");
        Document result = new Document();
        MongoIterable localCollections = localdb.listCollectionNames();
        MongoCursor iterator = localCollections.iterator();
        String oplog = null;
        
        while (iterator.hasNext()) {
            String collection = iterator.next().toString();
            if (collection.equals("oplog.rs")) {
                oplog = collection;
            }
            else if (collection.equals("oplog.$main")) {
                oplog = collection;
            }
        }
        
        if (oplog == null) {
            //This is not a replicaset
            return null;
        }
        
        MongoCollection ol = localdb.getCollection(oplog);
        Document ol_stats = localdb.runCommand(new Document().append("collStats", oplog).append("scale", 1).append("verbose", true));

        //Size of the oplog (MB)
        result.append("oplog_maxsizeMB", new BigDecimal(ol_stats.get("maxSize").toString()).longValue() / (1024 * 1024));
        result.append("oplog_usedMb", Math.ceil(new BigDecimal(ol_stats.get("size").toString()).doubleValue() / (1024 * 1024)));
        MongoIterable doc = ol.find().sort(new BasicDBObject("$natural", 1)).limit(1);
        MongoCursor<Document> cursor = doc.iterator();
        Document first = null;
        if (cursor.hasNext()) {
            first = cursor.next();
        }
        if (first == null) {
            return null;
        }
        cursor.close();
        doc = ol.find().sort(new BasicDBObject("$natural", -1)).limit(1);
        cursor = doc.iterator();
        Document last = null;
        if (cursor.hasNext()) {
            last = cursor.next();
        }
        if (last == null) {
            return null;
        }
        cursor.close();
        String firstStringTimestamp = Integer.toString(((BsonTimestamp) first.get("ts")).getTime());
        Long firstTimestamp = Long.parseLong(firstStringTimestamp);
        String lastStringTimestamp = Integer.toString(((BsonTimestamp) last.get("ts")).getTime());
        Long lastTimestamp = Long.parseLong(lastStringTimestamp);
        Long timeDiff = lastTimestamp - firstTimestamp;
        Integer timeDiffHours = Math.round(timeDiff / 36) / 100;

        //Oplog window (seconds)
        result.append("oplogWindowTimeDiff-Sec", timeDiff);
        result.append("oplogWindowtimeDiff-Hour", timeDiffHours);

        //Replication Lag: delay between a write operation on the primary and its copy to a secondary (milliseconds)
        //members.optimeDate[primary] â€“ members.optimeDate[secondary member] **
        //Replication headroom: difference between the primaryâ€™s oplog window and the replication lag of the secondary (milliseconds)
        //getReplicationInfo.timeDiff x 1000 â€“ (replSetGetStatus.members.optimeDate[primary] â€“ replSetGetStatus.members.optimeDate[secondary member])
        ArrayList<Document> members = (ArrayList<Document>) replSetStatus.get("members");

        Date primary = null;
        ArrayList<Pair<String, Date>> secondaries = new ArrayList();
        ArrayList<Pair<String, Date>> startup2 = new ArrayList();
        ArrayList<Pair<String, Date>> recovering = new ArrayList();
        for (Document mem : members) {
            if (mem.get("stateStr").toString().equalsIgnoreCase("PRIMARY")) {
                primary = new SimpleDateFormat("E MMM dd HH:mm:ss zzz yyyy").parse(mem.get("optimeDate").toString());
            }
            else if (mem.get("stateStr").toString().contains("SECONDARY")) {
                Pair<String, Date> temp = new Pair(mem.get("name").toString().replaceAll("\\.", "-").replaceAll(":", "_"), new SimpleDateFormat("E MMM dd HH:mm:ss zzz yyyy").parse(mem.get("optimeDate").toString()));
                secondaries.add(temp);
            }
            else if (mem.get("stateStr").toString().equalsIgnoreCase("STARTUP2")) {
                Pair<String, Date> temp = new Pair(mem.get("name").toString().replaceAll("\\.", "-").replaceAll(":", "_"), new SimpleDateFormat("E MMM dd HH:mm:ss zzz yyyy").parse(mem.get("optimeDate").toString()));
                startup2.add(temp);
            }
            else if (mem.get("stateStr").toString().equalsIgnoreCase("RECOVERING")) {
                Pair<String, Date> temp = new Pair(mem.get("name").toString().replaceAll("\\.", "-").replaceAll(":", "_"), new SimpleDateFormat("E MMM dd HH:mm:ss zzz yyyy").parse(mem.get("optimeDate").toString()));
                recovering.add(temp);
            }
        }

        if (primary != null) {
            for (Pair<String, Date> pair : secondaries) {
                Long diff = primary.getTime() - pair.getSecond().getTime();
                long headroom = (timeDiff * 1000) - diff;

                result.append("replicationLag-Sec.secondary." + pair.getFirst(), diff / 1000);
                result.append("replicationHeadroom-Sec.secondary." + pair.getFirst(), headroom / 1000);
            }
            for (Pair<String, Date> pair : startup2) {
                Long diff = primary.getTime() - pair.getSecond().getTime();
                long headroom = (timeDiff * 1000) - diff;

                result.append("replicationLag-Sec.startup2." + pair.getFirst(), diff / 1000);
                result.append("replicationHeadroom-Sec.startup2." + pair.getFirst(), headroom / 1000);
            }
            for (Pair<String, Date> pair : recovering) {
                long diff = primary.getTime() - pair.getSecond().getTime();
                long headroom = (timeDiff * 1000) - diff;

                result.append("replicationLag-Sec.recovering." + pair.getFirst(), diff / 1000);
                result.append("replicationHeadroom-Sec.recovering." + pair.getFirst(), headroom / 1000);
            }
        }

        result.append("ok", 1);

        return result;
    }

}
