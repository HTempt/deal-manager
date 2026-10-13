package com.dealmanager.service;

import com.dealmanager.domain.ModelCallLog;
import com.dealmanager.mapper.ModelCallLogMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class ModelCallLogService {
    private final ModelCallLogMapper mapper;

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void save(ModelCallLog record) {
        mapper.insert(record);
    }
}
