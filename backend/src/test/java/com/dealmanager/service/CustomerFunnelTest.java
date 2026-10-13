package com.dealmanager.service;

import com.dealmanager.domain.Customer;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class CustomerFunnelTest {
    @Test
    void advancesOnlyKnownStagesAndNeverLosesHistory() {
        Customer c = new Customer(); c.setStatus("ACTIVE");
        String[] stages = {"新客户", "需求沟通", "方案中", "报价中", "谈判中"};
        for (int rank = 0; rank < stages.length; rank++) {
            c.setStage(stages[rank]); CustomerFunnel.advance(c);
            assertEquals(rank, c.getFunnelStageRank());
        }
        c.setStage("需求沟通"); CustomerFunnel.advance(c); assertEquals(4, c.getFunnelStageRank());
        c.setStage("已流失"); c.setStatus("LOST"); CustomerFunnel.advance(c); assertEquals(4, c.getFunnelStageRank());
        c.setStage("未知阶段"); CustomerFunnel.advance(c); assertEquals(4, c.getFunnelStageRank());
        c.setStage(null); CustomerFunnel.advance(c); assertEquals(4, c.getFunnelStageRank());
    }

    @Test
    void skippedStagesAndRealDealsImplyAllPrecedingStagesButStageAloneCannotFakeDeal() {
        Customer c = new Customer(); c.setStatus("ACTIVE"); c.setStage("已成交");
        CustomerFunnel.advance(c); assertEquals(0, c.getFunnelStageRank());
        c.setStage("未知阶段"); CustomerFunnel.advance(c); assertEquals(0, c.getFunnelStageRank());
        c.setStage("报价中"); CustomerFunnel.advance(c); assertEquals(3, c.getFunnelStageRank());
        c.setStage("已成交"); CustomerFunnel.advance(c); assertEquals(3, c.getFunnelStageRank());
        c.setStatus("DEAL"); c.setStage(null); CustomerFunnel.advance(c); assertEquals(5, c.getFunnelStageRank());
        c.setStatus("LOST"); CustomerFunnel.advance(c); assertEquals(5, c.getFunnelStageRank());
    }
}
