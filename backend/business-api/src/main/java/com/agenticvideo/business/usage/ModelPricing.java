package com.agenticvideo.business.usage;

import java.math.BigDecimal;
import java.util.Set;

/** CNY list-price estimates, verified 2026-10-12; no allowance/discount or bill reconciliation. */
final class ModelPricing {
    static final String REFERENCE="2026-10-12 Beijing Bailian / DeepSeek peak list prices (upper estimate)";
    private ModelPricing() {}
    static BigDecimal estimate(String model,long input,long output,long cached,double seconds) {
        if("qwen3-asr-flash-filetrans".equals(model))return BigDecimal.valueOf(seconds).multiply(new BigDecimal("0.00022"));
        BigDecimal amount;
        if(Set.of("text-embedding-v4","text-embedding-v3").contains(model))amount=BigDecimal.valueOf(input).multiply(new BigDecimal("0.5"));
        else if("qwen3-vl-plus".equals(model)) {
            int multiplier=input>128000?3:input>32000?0:1;
            BigDecimal rate=multiplier==0?new BigDecimal("1.5"):BigDecimal.valueOf(multiplier);
            amount=BigDecimal.valueOf(input+output*10).multiply(rate);
        }else if(Set.of("deepseek-flash","deepseek-v4-flash","deepseek-v4.1-flash").contains(model))
            amount=BigDecimal.valueOf(input-cached).multiply(BigDecimal.valueOf(2)).add(BigDecimal.valueOf(cached).multiply(new BigDecimal("0.04"))).add(BigDecimal.valueOf(output).multiply(BigDecimal.valueOf(8)));
        else return null;
        return amount.movePointLeft(6);
    }
}
