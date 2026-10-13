package com.dealmanager.service;

import com.dealmanager.domain.Customer;

/** Cumulative progress: skipping a stage implies reaching all earlier stages. */
public final class CustomerFunnel {
    private CustomerFunnel() {}

    /** Pure in-memory helper; only a real DEAL status can advance to the deal rank. */
    public static void advance(Customer customer) {
        int current = "DEAL".equals(customer.getStatus()) ? 5 : stageRank(customer.getStage());
        int historical = customer.getFunnelStageRank() == null ? 0 : customer.getFunnelStageRank();
        customer.setFunnelStageRank(Math.max(historical, current));
    }

    private static int stageRank(String stage) {
        if (stage == null) return 0;
        return switch (stage) {
            case "需求沟通" -> 1;
            case "方案中" -> 2;
            case "报价中" -> 3;
            case "谈判中" -> 4;
            default -> 0;
        };
    }
}
