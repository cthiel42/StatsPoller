package com.pearson.statspoller.utilities.json_utils;

import com.pearson.statspoller.utilities.math_utils.MathUtilities;
import java.math.BigDecimal;

/**
 * @author Jeffrey Schmidt
 */
public class JsonBigDecimal extends BigDecimal {

    public JsonBigDecimal(String val) {
        super(val);
    }

    public JsonBigDecimal(BigDecimal bigDecimal) {
        super(bigDecimal.toPlainString());
    }

    @Override
    public String toString() {
        return MathUtilities.getFastPlainStringWithNoTrailingZeros(this);
    }
    
}