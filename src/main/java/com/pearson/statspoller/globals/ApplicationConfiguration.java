package com.pearson.statspoller.globals;

import com.opencsv.CSVReader;
import com.pearson.statspoller.external_metric_collectors.ExternalMetricCollector;
import java.io.StringReader;
import java.util.ArrayList;
import java.util.List;
import com.pearson.statspoller.internal_metric_collectors.apache_http.ApacheHttpMetricCollector;
import com.pearson.statspoller.internal_metric_collectors.file_counter.FileCounterMetricCollector;
import com.pearson.statspoller.internal_metric_collectors.jmx.JmxMetricCollector;
import com.pearson.statspoller.internal_metric_collectors.linux.ProcessCounter.ProcessCounterMetricCollector;
import com.pearson.statspoller.internal_metric_collectors.mongo.MongoMetricCollector;
import com.pearson.statspoller.internal_metric_collectors.mysql.MysqlMetricCollector;
import com.pearson.statspoller.metric_formats.graphite.GraphiteOutputModule;
import com.pearson.statspoller.metric_formats.opentsdb.OpenTsdbHttpOutputModule;
import com.pearson.statspoller.metric_formats.opentsdb.OpenTsdbTelnetOutputModule;
import com.pearson.statspoller.utilities.HierarchicalIniConfigurationWrapper;
import com.pearson.statspoller.utilities.StackTrace;
import java.net.InetAddress;
import org.apache.commons.lang3.SystemUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * @author Jeffrey Schmidt
 */
public class ApplicationConfiguration {

    private static final Logger logger = LoggerFactory.getLogger(ApplicationConfiguration.class.getName());
    
    public static final int VALUE_NOT_SET_CODE = -4444;
    
    private static boolean isInitializeSuccess_ = false; 
    
    private static HierarchicalIniConfigurationWrapper applicationConfiguration_ = null;
    
    private static boolean globalMetricNamePrefixEnabled_ = false;
    private static String globalMetricNamePrefixValue_ = null;
    private static long outputInterval_ = VALUE_NOT_SET_CODE;
    
    private static long checkOutputFilesInterval_ = VALUE_NOT_SET_CODE;
    private static boolean alwaysCheckOutputFiles_ = false;
    private static long maxMetricAge_ = VALUE_NOT_SET_CODE;
    private static boolean outputInternalMetricsToDisk_ = true;
    private static boolean legacyMode_ = false;
    
    private static final List<GraphiteOutputModule> graphiteOutputModules_ = new ArrayList<>();
    private static final List<OpenTsdbTelnetOutputModule> openTsdbTelnetOutputModules_ = new ArrayList<>();
    private static final List<OpenTsdbHttpOutputModule> openTsdbHttpOutputModules_ = new ArrayList<>();
    
    private static String statspollerMetricCollectorPrefix_ = null;
    private static boolean statspollerEnableJavaMetricCollector_ = false;
    private static long statspollerJavaMetricCollectorCollectionInterval_ = VALUE_NOT_SET_CODE;
        
    private static boolean linuxMetricCollectorEnable_ = false;
    private static String linuxProcLocation_ = null;
    private static String linuxSysLocation_ = null;
    private static long linuxMetricCollectorCollectionInterval_ = VALUE_NOT_SET_CODE;
    
    private static final List<String[]> processCounterPrefixesAndRegexes_ = new ArrayList<>();
    private static long processCounterMetricCollectorCollectionInterval_ = VALUE_NOT_SET_CODE;
    private static final List<FileCounterMetricCollector> fileCounterMetricCollectors_ = new ArrayList<>();
    private static final List<ExternalMetricCollector> externalMetricCollectors_ = new ArrayList<>();
    private static final List<JmxMetricCollector> jmxMetricCollectors_ = new ArrayList<>();
    private static final List<ApacheHttpMetricCollector> apacheHttpMetricCollectors_ = new ArrayList<>();
    private static final List<MongoMetricCollector> mongoMetricCollectors_ = new ArrayList<>();
    private static final List<MysqlMetricCollector> mysqlMetricCollectors_ = new ArrayList<>();
    
    private static long applicationStartTimeInMs_ = VALUE_NOT_SET_CODE;
    private static String hostname_ = null;
    
    public static boolean initialize(String filePathAndFilename, boolean isPrimary) {
        
        if (filePathAndFilename == null) {
            return false;
        }
        
        applicationConfiguration_ = new HierarchicalIniConfigurationWrapper(filePathAndFilename);
        
        if (!applicationConfiguration_.isValid()) {
            return false;
        }
        
        if (isPrimary) isInitializeSuccess_ = setPrimaryApplicationConfigurationValues();
        else setSecondaryApplicationConfigurationValues();
            
        return isInitializeSuccess_;
    }

    private static boolean setPrimaryApplicationConfigurationValues() {
        
        try {
            // get the OS's hostname
            hostname_ = getOsHostname();
           
            // get legacy mode -- reading this first since it is used by many other config settings
            // if in auto mode, then check the output_interval variable. 
            // assume no one would want to output less frequently than 1 day, so if output_interval is greater than 3600, then assume legacy_mode = true.
            String legacyModeString = applicationConfiguration_.safeGetString("legacy_mode", "auto");
            if (legacyModeString == null) legacyModeString = "auto";
            if (legacyModeString.equalsIgnoreCase("true")) legacyMode_ = true;
            else if (legacyModeString.equalsIgnoreCase("false")) legacyMode_ = false;
            else if (legacyModeString.equalsIgnoreCase("auto")) {
                double outputInterval = applicationConfiguration_.safeGetDouble("output_interval", 30);    
                legacyMode_ = (outputInterval > 3600);
            }
            
            // advanced core statspoller configuration values
            maxMetricAge_ = applicationConfiguration_.safeGetLong("max_metric_age", 90 * 1000); // remove?
            outputInternalMetricsToDisk_ = applicationConfiguration_.safeGetBoolean("output_internal_metrics_to_disk", true);
            alwaysCheckOutputFiles_ = applicationConfiguration_.safeGetBoolean("always_check_output_files", true);
            double checkOutputFilesInterval = applicationConfiguration_.safeGetDouble("check_output_files_interval", 5);
            checkOutputFilesInterval_ = legacyMode_ ? (long) checkOutputFilesInterval : (long) (checkOutputFilesInterval * 1000);    
            
            // core statspoller configuration values
            globalMetricNamePrefixEnabled_ = applicationConfiguration_.safeGetBoolean("global_metric_name_prefix_enabled", true);
            globalMetricNamePrefixValue_ = applicationConfiguration_.safeGetString("global_metric_name_prefix_value", getOsHostname()).replace("$HOSTNAME", hostname_);
            double outputInterval = applicationConfiguration_.safeGetDouble("output_interval", 30);            
            outputInterval_ = legacyMode_ ? (long) outputInterval : (long) (outputInterval * 1000);     
           
            // graphite configuration
            graphiteOutputModules_.addAll(readLegacyGraphiteOutputModule());
            graphiteOutputModules_.addAll(readGraphiteOutputModules());

            // opentsdb configuration
            openTsdbTelnetOutputModules_.addAll(readOpenTsdbTelnetOutputModules());
            
            // opentsdb configuration
            openTsdbHttpOutputModules_.addAll(readOpenTsdbHttpOutputModules());

            // native (built-in) server-info collector
            statspollerMetricCollectorPrefix_ = applicationConfiguration_.safeGetString("statspoller_metric_collector_prefix", "StatsPoller");
            statspollerEnableJavaMetricCollector_ = applicationConfiguration_.safeGetBoolean("statspoller_enable_java_metric_collector", true);
            double statspollerJavaMetricCollectorCollectionInterval = applicationConfiguration_.safeGetDouble("statspoller_java_metric_collector_collection_interval", 30);
            statspollerJavaMetricCollectorCollectionInterval_ = legacyMode_ ? (long) statspollerJavaMetricCollectorCollectionInterval : (long) (statspollerJavaMetricCollectorCollectionInterval * 1000);     
            
            // linux (built-in) metric collectors
            String linuxMetricCollectorEnable = applicationConfiguration_.safeGetString("linux_metric_collector_enable", "auto");
            if (linuxMetricCollectorEnable.equalsIgnoreCase("auto") && SystemUtils.IS_OS_LINUX) linuxMetricCollectorEnable_ = true;
            else linuxMetricCollectorEnable_ = linuxMetricCollectorEnable.equalsIgnoreCase("true");
            linuxProcLocation_ = applicationConfiguration_.safeGetString("linux_proc_location", "/proc");
            linuxSysLocation_ = applicationConfiguration_.safeGetString("linux_sys_location", "/sys");
            double linuxMetricCollectorCollectionInterval = applicationConfiguration_.safeGetDouble("linux_metric_collector_collection_interval", 30);
            linuxMetricCollectorCollectionInterval_ = legacyMode_ ? (long) linuxMetricCollectorCollectionInterval : (long) (linuxMetricCollectorCollectionInterval * 1000);
            
            double processCounterInterval = applicationConfiguration_.safeGetDouble("process_counter_interval", 30);
            processCounterMetricCollectorCollectionInterval_ = legacyMode_ ? (long) processCounterInterval : (long) (processCounterInterval * 1000); 
            
            // record application startup time
            applicationStartTimeInMs_ = System.currentTimeMillis();
            
            // startup non-core metric collectors
            setSecondaryApplicationConfigurationValues();
            
            return true;    
        } 
        catch (Exception e) {
            logger.error(e.toString() + System.lineSeparator() + StackTrace.getStringFromStackTrace(e));
            return false;
        }
        
    }
    
    private static boolean setSecondaryApplicationConfigurationValues() {
        
        try {
            // load the configurations of the external metric collectors (metric collection plugins)
            externalMetricCollectors_.addAll(readExternalMetricCollectors(legacyMode_));
            
            // add file counter collectors
            fileCounterMetricCollectors_.addAll(readFileCounterMetricCollectors(legacyMode_));

            // add linux process counter collectors
            processCounterPrefixesAndRegexes_.addAll(readProcessCounterPrefixesAndRegexes());
            
            // add jmx collectors
            JmxMetricCollector jmxMetricCollector = readJmxMetricCollector("", legacyMode_);
            if (jmxMetricCollector != null) jmxMetricCollectors_.add(jmxMetricCollector);
            for (int i = -1; i <= 10000; i++) {
                String collectorSuffix = "_" + (i + 1);
                jmxMetricCollector = readJmxMetricCollector(collectorSuffix, legacyMode_);
                if (jmxMetricCollector != null) jmxMetricCollectors_.add(jmxMetricCollector);
            }
            
            // add apache http collectors
            ApacheHttpMetricCollector apacheHttpMetricCollector = readApacheHttpMetricCollector("", legacyMode_);
            if (apacheHttpMetricCollector != null) apacheHttpMetricCollectors_.add(apacheHttpMetricCollector);
            for (int i = -1; i <= 10000; i++) {
                String collectorSuffix = "_" + (i + 1);
                apacheHttpMetricCollector = readApacheHttpMetricCollector(collectorSuffix, legacyMode_);
                if (apacheHttpMetricCollector != null) apacheHttpMetricCollectors_.add(apacheHttpMetricCollector);
            }
            
            // add mongo collectors
            MongoMetricCollector mongoMetricCollector = readMongoMetricCollector("", legacyMode_);
            if (mongoMetricCollector != null) mongoMetricCollectors_.add(mongoMetricCollector);
            for (int i = -1; i <= 10000; i++) {
                String collectorSuffix = "_" + (i + 1);
                mongoMetricCollector = readMongoMetricCollector(collectorSuffix, legacyMode_);
                if (mongoMetricCollector != null) mongoMetricCollectors_.add(mongoMetricCollector);
            }
            
            // add mysql collectors
            MysqlMetricCollector mysqlMetricCollector = readMysqlMetricCollector("", legacyMode_);
            if (mysqlMetricCollector != null) mysqlMetricCollectors_.add(mysqlMetricCollector);
            for (int i = -1; i <= 10000; i++) {
                String collectorSuffix = "_" + (i + 1);
                mysqlMetricCollector = readMysqlMetricCollector(collectorSuffix, legacyMode_);
                if (mysqlMetricCollector != null) mysqlMetricCollectors_.add(mysqlMetricCollector);
            }
            if (!mysqlMetricCollectors_.isEmpty()) loadMySQLDrivers();
            
            return true;    
        } 
        catch (Exception e) {
            logger.error(e.toString() + System.lineSeparator() + StackTrace.getStringFromStackTrace(e));
            return false;
        }
        
    }
    
    private static String getOsHostname() {
        
        String hostname = System.getenv().get("COMPUTERNAME");
        if ((hostname != null) && !hostname.trim().equals("")) return hostname;

        hostname = System.getenv().get("HOSTNAME");
        if ((hostname != null) && !hostname.trim().equals("")) return hostname;

        try {
            hostname = InetAddress.getLocalHost().getHostName();
            if ((hostname != null) && !hostname.trim().equals("")) return hostname;
        }
        catch (Exception e) {}
        
        return "UNKNOWN-HOST";
    }
    
    private static List<GraphiteOutputModule> readLegacyGraphiteOutputModule() {

        List<GraphiteOutputModule> graphiteOutputModules = new ArrayList<>();
        
        Boolean doesLegacyGraphiteOutputConfigExist  = applicationConfiguration_.safeGetBoolean("graphite_output_enabled", null);
        if (doesLegacyGraphiteOutputConfigExist == null) return graphiteOutputModules;
        
        boolean graphiteOutputEnabled  = applicationConfiguration_.safeGetBoolean("graphite_output_enabled", false);
        String graphiteHost  = applicationConfiguration_.safeGetString("graphite_host", "localhost");
        int graphitePort  = applicationConfiguration_.safeGetInt("graphite_port", 2003);
        int graphiteMaxBatchSize  = applicationConfiguration_.safeGetInt("graphite_max_batch_size", 1000);
        int graphiteSendRetryAttempts  = applicationConfiguration_.safeGetInt("graphite_send_retry_attempts", 2);
        String uniqueId = "Graphite-LegacyConfig";
        
        GraphiteOutputModule graphiteOutputModule = new GraphiteOutputModule(graphiteOutputEnabled, graphiteHost, graphitePort,
                graphiteSendRetryAttempts, graphiteMaxBatchSize, true, true, uniqueId);
        
        graphiteOutputModules.add(graphiteOutputModule);
        
        return graphiteOutputModules;
    }
    
    private static List<GraphiteOutputModule> readGraphiteOutputModules() {
        
        List<GraphiteOutputModule> graphiteOutputModules = new ArrayList<>();
        
        for (int i = -1; i < 10000; i++) {
            String graphiteOutputModuleKey = "graphite_output_module_" + (i + 1);
            String graphiteOutputModuleValue = applicationConfiguration_.safeGetString(graphiteOutputModuleKey, null);
            
            if (graphiteOutputModuleValue == null) continue;
            
            try {
                CSVReader reader = new CSVReader(new StringReader(graphiteOutputModuleValue));
                List<String[]> csvValuesArray = reader.readAll();

                if ((csvValuesArray != null) && !csvValuesArray.isEmpty() && (csvValuesArray.get(0) != null)) {
                    String[] csvValues = csvValuesArray.get(0);

                    if (csvValues.length >= 4) {                                
                        boolean isOutputEnabled = Boolean.valueOf(csvValues[0]);
                        String host = csvValues[1];
                        int port = Integer.valueOf(csvValues[2]);
                        int numSendRetryAttempts = Integer.valueOf(csvValues[3]);
                        
                        int maxMetricsPerMessage = 1000;
                        if (csvValues.length > 4) maxMetricsPerMessage = Integer.valueOf(csvValues[4]);
                        
                        boolean sanitizeMetrics = false;
                        if (csvValues.length > 5) sanitizeMetrics = Boolean.valueOf(csvValues[5]);
                        
                        boolean substituteCharacters = false;
                        if (csvValues.length > 6) substituteCharacters = Boolean.valueOf(csvValues[6]);
                        
                        String uniqueId = "Graphite-" + (i+1);
                        
                        GraphiteOutputModule graphiteOutputModule = new GraphiteOutputModule(isOutputEnabled, host, port, 
                                numSendRetryAttempts, maxMetricsPerMessage, sanitizeMetrics, substituteCharacters, uniqueId);
                        
                        graphiteOutputModules.add(graphiteOutputModule);
                    }
                }
            }
            catch (Exception e) {
                logger.error(e.toString() + System.lineSeparator() + StackTrace.getStringFromStackTrace(e));
            }
        }
        
        return graphiteOutputModules;
    }

    private static List<OpenTsdbTelnetOutputModule> readOpenTsdbTelnetOutputModules() {
        
        List<OpenTsdbTelnetOutputModule> openTsdbTelnetOutputModules = new ArrayList<>();
        
        for (int i = -1; i < 10000; i++) {
            String openTsdbTelnetOutputModuleKey = "opentsdb_telnet_output_module_" + (i + 1);
            String openTsdbTelnetOutputModuleValue = applicationConfiguration_.safeGetString(openTsdbTelnetOutputModuleKey, null);
            
            if (openTsdbTelnetOutputModuleValue == null) continue;
            
            try {
                CSVReader reader = new CSVReader(new StringReader(openTsdbTelnetOutputModuleValue));
                List<String[]> csvValuesArray = reader.readAll();

                if ((csvValuesArray != null) && !csvValuesArray.isEmpty() && (csvValuesArray.get(0) != null)) {
                    String[] csvValues = csvValuesArray.get(0);

                    if (csvValues.length >= 4) {                                
                        boolean isOutputEnabled = Boolean.valueOf(csvValues[0]);
                        String host = csvValues[1];
                        int port = Integer.valueOf(csvValues[2]);
                        int numSendRetryAttempts = Integer.valueOf(csvValues[3]);
                        
                        boolean sanitizeMetrics = false;
                        if (csvValues.length > 4) sanitizeMetrics = Boolean.valueOf(csvValues[4]);
                        
                        String uniqueId = "OpenTSDB-Telnet-" + (i+1);
                        
                        OpenTsdbTelnetOutputModule openTsdbTelnetOutputModule = new OpenTsdbTelnetOutputModule(isOutputEnabled, host, port, 
                                numSendRetryAttempts, sanitizeMetrics, uniqueId);
                        
                        openTsdbTelnetOutputModules.add(openTsdbTelnetOutputModule);
                    }
                }
            }
            catch (Exception e) {
                logger.error(e.toString() + System.lineSeparator() + StackTrace.getStringFromStackTrace(e));
            }
        }
        
        return openTsdbTelnetOutputModules;
    }
    
    private static List<OpenTsdbHttpOutputModule> readOpenTsdbHttpOutputModules() {
        
        List<OpenTsdbHttpOutputModule> openTsdbHttpOutputModules = new ArrayList<>();
        
        for (int i = -1; i < 10000; i++) {
            String openTsdbHttpOutputModuleKey = "opentsdb_http_output_module_" + (i + 1);
            String openTsdbHttpOutputModuleValue = applicationConfiguration_.safeGetString(openTsdbHttpOutputModuleKey, null);
            
            if (openTsdbHttpOutputModuleValue == null) continue;
            
            try {
                CSVReader reader = new CSVReader(new StringReader(openTsdbHttpOutputModuleValue));
                List<String[]> csvValuesArray = reader.readAll();

                if ((csvValuesArray != null) && !csvValuesArray.isEmpty() && (csvValuesArray.get(0) != null)) {
                    String[] csvValues = csvValuesArray.get(0);

                    if (csvValues.length >= 4) {                                
                        boolean isOutputEnabled = Boolean.valueOf(csvValues[0]);
                        String url = csvValues[1];
                        int numSendRetryAttempts = Integer.valueOf(csvValues[2]);
                        int maxMetricsPerMessage = Integer.valueOf(csvValues[3]);
                        
                        boolean sanitizeMetrics = false;
                        if (csvValues.length > 4) sanitizeMetrics = Boolean.valueOf(csvValues[4]);
                        
                        String uniqueId = "OpenTSDB-HTTP-" + (i+1);
                        
                        OpenTsdbHttpOutputModule openTsdbHttpOutputModule = new OpenTsdbHttpOutputModule(isOutputEnabled, url, numSendRetryAttempts, 
                                maxMetricsPerMessage, sanitizeMetrics, uniqueId);
                        openTsdbHttpOutputModules.add(openTsdbHttpOutputModule);
                    }
                }
            }
            catch (Exception e) {
                logger.error(e.toString() + System.lineSeparator() + StackTrace.getStringFromStackTrace(e));
            }
        }
        
        return openTsdbHttpOutputModules;
    }

    private static List<String[]> readProcessCounterPrefixesAndRegexes() {
       
        List<String[]> processCounterPrefixesAndRegexes = new ArrayList<>();
        
        try {
            List<Object> processCounterRegexConfigs = applicationConfiguration_.safeGetList("process_counter_regex", new ArrayList<>());
            if (processCounterRegexConfigs != null) {
                for (Object processCounterRegexConfig : processCounterRegexConfigs) {
                    String[] processCounterMetricCollectorRegex = parseProcessCounterMetricCollectorRegexCsv((String) processCounterRegexConfig);
                    if (processCounterMetricCollectorRegex != null) processCounterPrefixesAndRegexes.add(processCounterMetricCollectorRegex);
                }
            }

            for (int i = -1; i <= 10000; i++) {
                String processCounterRegexConfig_Key = "process_counter_regex_" + (i + 1);
                String processCounterRegexConfig_Value = applicationConfiguration_.safeGetString(processCounterRegexConfig_Key, null);
                if (processCounterRegexConfig_Value == null) continue;

                String[] processCounterMetricCollectorRegex = parseProcessCounterMetricCollectorRegexCsv((String) processCounterRegexConfig_Value);
                if (processCounterMetricCollectorRegex != null) processCounterPrefixesAndRegexes.add(processCounterMetricCollectorRegex);
            }
        }
        catch (Exception e) {
            logger.error(e.toString() + System.lineSeparator() + StackTrace.getStringFromStackTrace(e));      
            return processCounterPrefixesAndRegexes;
        }
        
        return processCounterPrefixesAndRegexes;
    }
    
    private static String[] parseProcessCounterMetricCollectorRegexCsv(String processCounterMetricCollectorConfig) {
        
        if ((processCounterMetricCollectorConfig == null) || processCounterMetricCollectorConfig.isEmpty()) {
            return null;
        }

        try {
            CSVReader reader = new CSVReader(new StringReader(processCounterMetricCollectorConfig));
            List<String[]> csvValuesArray = reader.readAll();

            if ((csvValuesArray != null) && !csvValuesArray.isEmpty() && (csvValuesArray.get(0) != null)) {
                String[] csvValues = csvValuesArray.get(0);
            
                if (csvValues.length == 2) {  
                    if ((csvValues[0] == null) || csvValues[0].isEmpty() || (csvValues[1] == null) || csvValues[1].isEmpty()) return null;
                    
                    String[] processCounterMetricCollectorRegex = new String[2];
                    processCounterMetricCollectorRegex[0] = csvValues[0];
                    processCounterMetricCollectorRegex[1] = csvValues[1];
                    return processCounterMetricCollectorRegex;
                }
            }
        }
        catch (Exception e) {
            logger.error(e.toString() + System.lineSeparator() + StackTrace.getStringFromStackTrace(e));      
            return null;
        }
        
        return null;
    }
    
    private static List<FileCounterMetricCollector> readFileCounterMetricCollectors(boolean legacyMode) {
        
        List<FileCounterMetricCollector> fileCounterMetricCollectors = new ArrayList<>();

        try {
            List<Object> fileCounterMetricCollectorConfigs = applicationConfiguration_.safeGetList("file_counter", new ArrayList<>());
            if (fileCounterMetricCollectorConfigs != null) {
                for (Object fileCounterMetricCollectorConfig : fileCounterMetricCollectorConfigs) {
                    FileCounterMetricCollector fileCounterMetricCollector = parseFileCounterMetricCollectorCsv((String) fileCounterMetricCollectorConfig, legacyMode);
                    if (fileCounterMetricCollector != null) fileCounterMetricCollectors.add(fileCounterMetricCollector);
                }
            }

            for (int i = -1; i <= 10000; i++) {
                String fileCounterMetricCollector_Key = "file_counter_" + (i + 1);
                String fileCounterMetricCollector_Value = applicationConfiguration_.safeGetString(fileCounterMetricCollector_Key, null);

                if (fileCounterMetricCollector_Value == null) continue;

                FileCounterMetricCollector fileCounterMetricCollector = parseFileCounterMetricCollectorCsv(fileCounterMetricCollector_Value, legacyMode);
                if (fileCounterMetricCollector != null) fileCounterMetricCollectors.add(fileCounterMetricCollector);
            }
        }
        catch (Exception e) {
            logger.error(e.toString() + System.lineSeparator() + StackTrace.getStringFromStackTrace(e));      
            return null;
        }
        
        return fileCounterMetricCollectors;
    }
    
    private static FileCounterMetricCollector parseFileCounterMetricCollectorCsv(String fileCounterMetricCollectorConfig, boolean legacyMode) {
        
        if ((fileCounterMetricCollectorConfig == null) || fileCounterMetricCollectorConfig.isEmpty()) {
            return null;
        }

        try {
            CSVReader reader = new CSVReader(new StringReader(fileCounterMetricCollectorConfig));
            List<String[]> csvValuesArray = reader.readAll();

            if ((csvValuesArray != null) && !csvValuesArray.isEmpty() && (csvValuesArray.get(0) != null)) {
                String[] csvValues = csvValuesArray.get(0);
            
                if (csvValues.length == 4) {  
                    String path = csvValues[0];
                    String countSubdirectories = csvValues[1];
                    boolean countSubdirectories_Boolean = Boolean.parseBoolean(countSubdirectories);
                    double collectionIntervalInSeconds = Long.valueOf(csvValues[2].trim());
                    long collectionIntervalInMilliseconds = legacyMode ? (long) collectionIntervalInSeconds  : (long) (collectionIntervalInSeconds * 1000); 
                    String metricPrefix = csvValues[3];
                    String outputFile = "./output/" + "filecounter_" + metricPrefix + ".out";
                    
                    FileCounterMetricCollector fileCounterMetricCollector = new FileCounterMetricCollector(true,
                            collectionIntervalInMilliseconds, "FileCounter." + metricPrefix, outputFile, outputInternalMetricsToDisk_,
                            path, countSubdirectories_Boolean);
                    
                    return fileCounterMetricCollector;
                }
            }
        }
        catch (Exception e) {
            logger.error(e.toString() + System.lineSeparator() + StackTrace.getStringFromStackTrace(e));      
            return null;
        }
        
        return null;
    }
    
    private static List<ExternalMetricCollector> readExternalMetricCollectors(boolean legacyMode) {
        
        List<ExternalMetricCollector> externalMetricCollectors = new ArrayList<>();

        try {
            List<Object> metricCollectorConfigs = applicationConfiguration_.safeGetList("metric_collector", new ArrayList<>());

            if (metricCollectorConfigs != null) {
                for (Object metricCollectorsConfig : metricCollectorConfigs) {
                    ExternalMetricCollector externalMetricCollector = parseExternalMetricCollectorCsv((String) metricCollectorsConfig, legacyMode);
                    if (externalMetricCollector != null) externalMetricCollectors.add(externalMetricCollector);
                }
            }

            for (int i = -1; i <= 10000; i++) {
                String metricCollectorKey = "metric_collector_" + (i + 1);
                String metricCollectorValue = applicationConfiguration_.safeGetString(metricCollectorKey, null);
                if (metricCollectorValue == null) continue;
                ExternalMetricCollector externalMetricCollector = parseExternalMetricCollectorCsv(metricCollectorValue, legacyMode);
                if (externalMetricCollector != null) externalMetricCollectors.add(externalMetricCollector);
            }
        }
        catch (Exception e) {
            logger.error(e.toString() + System.lineSeparator() + StackTrace.getStringFromStackTrace(e));      
            return null;
        }
        
        return externalMetricCollectors;
    }
    
    private static ExternalMetricCollector parseExternalMetricCollectorCsv(String metricCollectorsConfig, boolean legacyMode) {
        
        if (metricCollectorsConfig == null) {
            return null;
        }
        
        try {
            metricCollectorsConfig = metricCollectorsConfig.replace("\\", "\\\\");

            CSVReader reader = new CSVReader(new StringReader(metricCollectorsConfig));
            List<String[]> csvValuesArray = reader.readAll();

            if ((csvValuesArray != null) && !csvValuesArray.isEmpty() && (csvValuesArray.get(0) != null)) {
                String[] csvValues = csvValuesArray.get(0);

                if (csvValues.length == 4) {
                    String programPathAndFilename = csvValues[0].trim();
                    double collectionIntervalInSeconds = Long.valueOf(csvValues[1].trim());
                    long collectionIntervalInMilliseconds = legacyMode ? (long) collectionIntervalInSeconds  : (long) (collectionIntervalInSeconds * 1000);     
                    String outputPathAndFilename = csvValues[2].trim();
                    String metricPrefix = csvValues[3].trim();

                    ExternalMetricCollector externalMetricCollector = new ExternalMetricCollector(programPathAndFilename, collectionIntervalInMilliseconds, outputPathAndFilename, metricPrefix);
                    return externalMetricCollector;
                }
            }
        }
        catch (Exception e) {
            logger.error(e.toString() + System.lineSeparator() + StackTrace.getStringFromStackTrace(e));
        }
        
        return null;
    }
    
    private static JmxMetricCollector readJmxMetricCollector(String collectorSuffix, boolean legacyMode) {
        
        if (applicationConfiguration_ == null) {
            return null;
        }
        
        if (collectorSuffix == null) collectorSuffix = "";
        
        try {
            String jmxEnabledKey = "jmx_enabled" + collectorSuffix;
            boolean jmxEnabledValue = applicationConfiguration_.safeGetBoolean(jmxEnabledKey, false);
            if (!jmxEnabledValue) return null;

            String jmxHostKey = "jmx_host" + collectorSuffix;
            String jmxHostValue = applicationConfiguration_.safeGetString(jmxHostKey, null);

            String jmxPortKey = "jmx_port" + collectorSuffix;
            String tempValue = applicationConfiguration_.safeGetString(jmxPortKey, null);
            int jmxPortValue = -1;
            if ((tempValue != null) && !tempValue.isEmpty()) jmxPortValue = applicationConfiguration_.safeGetInteger(jmxPortKey, -1);

            String jmxServiceUrlKey = "jmx_service_url" + collectorSuffix;
            String jmxServiceUrlValue = applicationConfiguration_.safeGetString(jmxServiceUrlKey, null);

            String jmxUsernameKey = "jmx_username" + collectorSuffix;
            String jmxUsernameValue = applicationConfiguration_.safeGetString(jmxUsernameKey, "");

            String jmxPasswordKey = "jmx_password" + collectorSuffix;
            String jmxPasswordValue = applicationConfiguration_.safeGetString(jmxPasswordKey, "");

            String jmxNumConnectionAttemptRetriesKey = "jmx_num_connection_attempt_retries" + collectorSuffix;
            int jmxNumConnectionAttemptRetriesValue = applicationConfiguration_.safeGetInteger(jmxNumConnectionAttemptRetriesKey, 3);

            String jmxSleepAfterConnectTimeKey = "jmx_sleep_after_connect_time" + collectorSuffix;
            double jmxSleepAfterConnectTimeValue = applicationConfiguration_.safeGetDouble(jmxSleepAfterConnectTimeKey, 30);
            long jmxSleepAfterConnectTimeValue_Long = legacyMode ? (long) jmxSleepAfterConnectTimeValue : (long) (jmxSleepAfterConnectTimeValue * 1000);    

            String jmxQueryMetricTreeKey = "jmx_query_metric_tree" + collectorSuffix;
            double jmxQueryMetricTreeValue = applicationConfiguration_.safeGetDouble(jmxQueryMetricTreeKey, 300);
            long jmxQueryMetricTreeValue_Long = legacyMode ? (long) jmxQueryMetricTreeValue : (long) (jmxQueryMetricTreeValue * 1000);    
                    
            String jmxCollectStringAttributesKey = "jmx_collect_string_attributes" + collectorSuffix;
            Boolean jmxCollectStringAttributesValue = applicationConfiguration_.safeGetBoolean(jmxCollectStringAttributesKey, false);

            String jmxBlacklistObjectNameRegexsKey = "jmx_blacklist_objectname_regex" + collectorSuffix;
            List<Object> jmxBlacklistObjectNameRegexsValue = applicationConfiguration_.safeGetList(jmxBlacklistObjectNameRegexsKey, null);
            List<String> jmxBlacklistObjectNameRegexsValueStrings = new ArrayList<>();
            if (jmxBlacklistObjectNameRegexsValue != null) {  
                for (Object object : jmxBlacklistObjectNameRegexsValue) {
                    jmxBlacklistObjectNameRegexsValueStrings.add((String) object);
                }
            }

            String jmxBlacklistRegexsKey = "jmx_blacklist_regex" + collectorSuffix;
            List<Object> jmxBlacklistRegexsValue = applicationConfiguration_.safeGetList(jmxBlacklistRegexsKey, null);
            List<String> jmxBlacklistRegexsValueStrings = new ArrayList<>();
            if (jmxBlacklistRegexsValue != null) {  
                for (Object object : jmxBlacklistRegexsValue) {
                    jmxBlacklistRegexsValueStrings.add((String) object);
                }
            }

            String jmxWhitelistRegexsKey = "jmx_whitelist_regex" + collectorSuffix;
            List<Object> jmxWhitelistRegexsValue = applicationConfiguration_.safeGetList(jmxWhitelistRegexsKey, null);
            List<String> jmxWhitelistRegexsValueStrings = new ArrayList<>();
            if (jmxWhitelistRegexsValue != null) {
                for (Object object : jmxWhitelistRegexsValue) {
                    jmxWhitelistRegexsValueStrings.add((String) object);
                }
            }

            String jmxCollectionIntervalKey = "jmx_collection_interval" + collectorSuffix;
            double jmxCollectionIntervalValue = applicationConfiguration_.safeGetDouble(jmxCollectionIntervalKey, 30);
            long jmxCollectionIntervalValue_Long = legacyMode ? (long) jmxCollectionIntervalValue : (long) (jmxCollectionIntervalValue * 1000);    

            String jmxDerivedMetricsEnabledKey = "jmx_derived_metrics_enabled" + collectorSuffix;
            boolean jmxDerivedMetricsEnabledValue = applicationConfiguration_.safeGetBoolean(jmxDerivedMetricsEnabledKey, true);

            String jmxMetricPrefixKey = "jmx_metric_prefix" + collectorSuffix;
            String jmxMetricPrefixValue = applicationConfiguration_.safeGetString(jmxMetricPrefixKey, "JMX");

            String jmxOutputFileValue = "./output/" + "jmx_" + jmxMetricPrefixValue + ".out";

            JmxMetricCollector jmxMetricCollector = new JmxMetricCollector(jmxEnabledValue, 
                    jmxCollectionIntervalValue_Long, jmxMetricPrefixValue, jmxOutputFileValue, outputInternalMetricsToDisk_,
                    jmxHostValue, jmxPortValue, jmxServiceUrlValue, jmxNumConnectionAttemptRetriesValue, 
                    jmxSleepAfterConnectTimeValue_Long, jmxQueryMetricTreeValue_Long, 
                    jmxCollectStringAttributesValue, jmxDerivedMetricsEnabledValue,
                    jmxUsernameValue, jmxPasswordValue, jmxBlacklistObjectNameRegexsValueStrings, 
                    jmxBlacklistRegexsValueStrings, jmxWhitelistRegexsValueStrings);

            return jmxMetricCollector;
        }
        catch (Exception e) {
            logger.error(e.toString() + System.lineSeparator() + StackTrace.getStringFromStackTrace(e));      
            return null;
        }
    }
    
    private static ApacheHttpMetricCollector readApacheHttpMetricCollector(String collectorSuffix, boolean legacyMode) {
        
        if (applicationConfiguration_ == null) {
            return null;
        }
        
        if (collectorSuffix == null) collectorSuffix = "";

        try {
            String apacheEnabledKey = "apachehttp_enabled" + collectorSuffix;
            boolean apacheEnabledValue = applicationConfiguration_.safeGetBoolean(apacheEnabledKey, false);
            if (!apacheEnabledValue) return null;
            
            String apacheProtocolKey = "apachehttp_protocol" + collectorSuffix;
            String apacheProtocolValue = applicationConfiguration_.safeGetString(apacheProtocolKey, "http");
            
            String apacheHostKey = "apachehttp_host" + collectorSuffix;
            String apacheHostValue = applicationConfiguration_.safeGetString(apacheHostKey, "127.0.0.1");

            String apachePortKey = "apachehttp_port" + collectorSuffix;
            int apachePortValue = applicationConfiguration_.safeGetInteger(apachePortKey, 80);
            
            String apacheCollectionIntervalKey = "apachehttp_collection_interval" + collectorSuffix;
            double apacheHttpCollectionIntervalValue = applicationConfiguration_.safeGetDouble(apacheCollectionIntervalKey, 30);
            long apacheHttpCollectionIntervalValue_Long = legacyMode ? (long) apacheHttpCollectionIntervalValue : (long) (apacheHttpCollectionIntervalValue * 1000);    

            String apacheMetricPrefixKey = "apachehttp_metric_prefix" + collectorSuffix;
            String apacheHttpMetricPrefixValue = applicationConfiguration_.safeGetString(apacheMetricPrefixKey, "ApacheHttp");

            String apacheOutputFileValue = "./output/" + "apachehttp_" + apacheHttpMetricPrefixValue + ".out";
            
            if (apacheEnabledValue && (apacheHostValue != null) && (apachePortValue != -1)) {
                ApacheHttpMetricCollector apacheHttpMetricCollector = new ApacheHttpMetricCollector(apacheEnabledValue,
                        apacheHttpCollectionIntervalValue_Long, apacheHttpMetricPrefixValue, apacheOutputFileValue, outputInternalMetricsToDisk_,
                        apacheProtocolValue, apacheHostValue, apachePortValue);
               
                return apacheHttpMetricCollector;
            }
            else return null;
        }
        catch (Exception e) {
            logger.error(e.toString() + System.lineSeparator() + StackTrace.getStringFromStackTrace(e));      
            return null;
        }
    }
    
    private static MongoMetricCollector readMongoMetricCollector(String collectorSuffix, boolean legacyMode) {
        
        if (applicationConfiguration_ == null) {
            return null;
        }
        
        if (collectorSuffix == null) collectorSuffix = "";

        try {
            String mongoEnabledKey = "mongo_enabled" + collectorSuffix;
            boolean mongoEnabledValue = applicationConfiguration_.safeGetBoolean(mongoEnabledKey, false);
            if (!mongoEnabledValue) return null;
            
            String mongoHostKey = "mongo_host" + collectorSuffix;
            String mongoHostValue = applicationConfiguration_.safeGetString(mongoHostKey, "127.0.0.1");

            String mongoPortKey = "mongo_port" + collectorSuffix;
            int mongoPortValue = applicationConfiguration_.safeGetInteger(mongoPortKey, 27017);
            
            String mongoUsernameKey = "mongo_username" + collectorSuffix;
            String mongoUsernameValue = applicationConfiguration_.safeGetString(mongoUsernameKey, "");
            
            String mongoPasswordKey = "mongo_password" + collectorSuffix;
            String mongoPasswordValue = applicationConfiguration_.safeGetString(mongoPasswordKey, "");
            
            String mongoCollectionIntervalKey = "mongo_collection_interval" + collectorSuffix;
            double mongoCollectionIntervalValue = applicationConfiguration_.safeGetDouble(mongoCollectionIntervalKey, 60);
            long mongoCollectionIntervalValue_Long = legacyMode ? (long) mongoCollectionIntervalValue : (long) (mongoCollectionIntervalValue * 1000);    

            String mongoMetricPrefixKey = "mongo_metric_prefix" + collectorSuffix;
            String mongoMetricPrefixValue = applicationConfiguration_.safeGetString(mongoMetricPrefixKey, "Mongo");

            String mongoOutputFileValue = "./output/" + "mongo_" + mongoMetricPrefixValue + ".out";
            
            if (mongoEnabledValue && (mongoHostValue != null) && (mongoPortValue != -1)) {
                MongoMetricCollector mongoMetricCollector = new MongoMetricCollector(mongoEnabledValue, 
                        mongoCollectionIntervalValue_Long, mongoMetricPrefixValue, mongoOutputFileValue, outputInternalMetricsToDisk_,
                        mongoHostValue, mongoPortValue, mongoUsernameValue, mongoPasswordValue);
               
                return mongoMetricCollector;
            }
            else return null;
        }
        catch (Exception e) {
            logger.error(e.toString() + System.lineSeparator() + StackTrace.getStringFromStackTrace(e));      
            return null;
        }
    }

    private static MysqlMetricCollector readMysqlMetricCollector(String collectorSuffix, boolean legacyMode) {
        
        if (applicationConfiguration_ == null) {
            return null;
        }
        
        if (collectorSuffix == null) collectorSuffix = "";

        try {
            String mysqlEnabledKey = "mysql_enabled" + collectorSuffix;
            boolean mysqlEnabledValue = applicationConfiguration_.safeGetBoolean(mysqlEnabledKey, false);
            if (!mysqlEnabledValue) return null;
            
            String mysqlHostKey = "mysql_host" + collectorSuffix;
            String mysqlHostValue = applicationConfiguration_.safeGetString(mysqlHostKey, "127.0.0.1");

            String mysqlPortKey = "mysql_port" + collectorSuffix;
            int mysqlPortValue = applicationConfiguration_.safeGetInteger(mysqlPortKey, 3306);
            
            String mysqlUsernameKey = "mysql_username" + collectorSuffix;
            String mysqlUsernameValue = applicationConfiguration_.safeGetString(mysqlUsernameKey, "");
            
            String mysqlPasswordKey = "mysql_password" + collectorSuffix;
            String mysqlPasswordValue = applicationConfiguration_.safeGetString(mysqlPasswordKey, "");
            
            String mysqlJdbcKey = "mysql_jdbc" + collectorSuffix;
            String mysqlJdbcValue = applicationConfiguration_.safeGetString(mysqlJdbcKey, "");
            
            String mysqlCollectionIntervalKey = "mysql_collection_interval" + collectorSuffix;
            double mysqlCollectionIntervalValue = applicationConfiguration_.safeGetDouble(mysqlCollectionIntervalKey, 60);
            long mysqlCollectionIntervalValue_Long = legacyMode ? (long) mysqlCollectionIntervalValue : (long) (mysqlCollectionIntervalValue * 1000);    

            String mysqlMetricPrefixKey = "mysql_metric_prefix" + collectorSuffix;
            String mysqlMetricPrefixValue = applicationConfiguration_.safeGetString(mysqlMetricPrefixKey, "MySQL");

            String mysqlOutputFileValue = "./output/" + "mysql_" + mysqlMetricPrefixValue + ".out";
            
            if (mysqlEnabledValue && (mysqlHostValue != null) && (mysqlPortValue != -1)) {
                MysqlMetricCollector mysqlMetricCollector = new MysqlMetricCollector(mysqlEnabledValue, 
                        mysqlCollectionIntervalValue_Long, mysqlMetricPrefixValue, mysqlOutputFileValue, outputInternalMetricsToDisk_,
                        mysqlHostValue, mysqlPortValue, mysqlUsernameValue, mysqlPasswordValue, mysqlJdbcValue);
               
                return mysqlMetricCollector;
            }
            else return null;
        }
        catch (Exception e) {
            logger.error(e.toString() + System.lineSeparator() + StackTrace.getStringFromStackTrace(e));      
            return null;
        }
    }
    
    private static void loadMySQLDrivers() {
        try {
            Class.forName("com.mysql.jdbc.Driver").newInstance();
        }
        catch (Exception e) {
            logger.error(e.toString() + System.lineSeparator() + StackTrace.getStringFromStackTrace(e));
        }
        
        try {
            Class.forName("org.mariadb.jdbc.Driver").newInstance();
        }
        catch (Exception e) {
            logger.error(e.toString() + System.lineSeparator() + StackTrace.getStringFromStackTrace(e));
        }
    }
    
    public static boolean isGlobalMetricNamePrefixEnabled() {
        return globalMetricNamePrefixEnabled_;
    }

    public static String getGlobalMetricNamePrefixValue() {
        return globalMetricNamePrefixValue_;
    }

    public static long getOutputInterval() {
        return outputInterval_;
    }

    public static long getCheckOutputFilesInterval() {
        return checkOutputFilesInterval_;
    }
    
    public static boolean isAlwaysCheckOutputFiles() {
        return alwaysCheckOutputFiles_;
    }

    public static long getMaxMetricAge() {
        return maxMetricAge_;
    }

    public static boolean isOutputInternalMetricsToDisk() {
        return outputInternalMetricsToDisk_;
    }

    public static List<GraphiteOutputModule> getGraphiteOutputModules() {
        if (graphiteOutputModules_ == null) return null;
        else return new ArrayList<>(graphiteOutputModules_);
    }

    public static List<OpenTsdbTelnetOutputModule> getOpenTsdbTelnetOutputModules() {
        if (openTsdbTelnetOutputModules_ == null) return null;
        else return new ArrayList<>(openTsdbTelnetOutputModules_);
    }

    public static List<OpenTsdbHttpOutputModule> getOpenTsdbHttpOutputModules() {
        if (openTsdbHttpOutputModules_ == null) return null;
        else return new ArrayList<>(openTsdbHttpOutputModules_);
    }
    
    public static String getStatspollerMetricCollectorPrefix() {
        return statspollerMetricCollectorPrefix_;
    }
    
    public static boolean isStatsPollerEnableJavaMetricCollector() {
        return statspollerEnableJavaMetricCollector_;
    }
    
    public static long getStatspollerJavaMetricCollectorCollectionInterval() {
        return statspollerJavaMetricCollectorCollectionInterval_;
    }
    
    public static boolean isLinuxMetricCollectorEnable() {
        return linuxMetricCollectorEnable_;
    }
    
    public static String getLinuxProcLocation() {
        return linuxProcLocation_;
    }

    public static String getLinuxSysLocation() {
        return linuxSysLocation_;
    }
    
    public static long getLinuxMetricCollectorCollectionInterval() {
        return linuxMetricCollectorCollectionInterval_;
    }

    public static List<String[]> getProcessCounterPrefixesAndRegexes() {
        if (processCounterPrefixesAndRegexes_ == null) return null;
        return new ArrayList<>(processCounterPrefixesAndRegexes_);
    }

    public static long getProcessCounterMetricCollectorCollectionInterval() {
        return processCounterMetricCollectorCollectionInterval_;
    }

    public static List<FileCounterMetricCollector> getFileCounterMetricCollectors() {
        if (fileCounterMetricCollectors_ == null) return null;
        return new ArrayList<>(fileCounterMetricCollectors_);
    }
    
    public static List<ExternalMetricCollector> getExternalMetricCollectors() {
        if (externalMetricCollectors_ == null) return null;
        return new ArrayList<>(externalMetricCollectors_);
    }

    public static List<JmxMetricCollector> getJmxMetricCollectors() {
        if (jmxMetricCollectors_ == null) return null;
        return new ArrayList<>(jmxMetricCollectors_);
    }

    public static List<ApacheHttpMetricCollector> getApacheHttpMetricCollectors() {
        if (apacheHttpMetricCollectors_ == null) return null;
        return new ArrayList<>(apacheHttpMetricCollectors_);
    }

    public static List<MongoMetricCollector> getMongoMetricCollectors() {
        if (mongoMetricCollectors_ == null) return null;
        return new ArrayList<>(mongoMetricCollectors_);
    }

    public static List<MysqlMetricCollector> getMysqlMetricCollectors() {
        if (mysqlMetricCollectors_ == null) return null;
        return new ArrayList<>(mysqlMetricCollectors_);
    }

    public static long getApplicationStartTimeInMs() {
        return applicationStartTimeInMs_;
    }

    public static String getHostname() {
        return hostname_;
    }

}
