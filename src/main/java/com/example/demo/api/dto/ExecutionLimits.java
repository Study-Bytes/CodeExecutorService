package com.example.demo.api.dto;

import com.fasterxml.jackson.annotation.JsonSetter;
import com.fasterxml.jackson.annotation.Nulls;

public class ExecutionLimits {

    @JsonSetter(nulls = Nulls.SKIP)
    private Integer timeLimitMs = 60000;

    @JsonSetter(nulls = Nulls.SKIP)
    private Integer memoryLimitMb = 256;

    @JsonSetter(nulls = Nulls.SKIP)
    private Integer outputLimitKb = 256;

    public Integer getTimeLimitMs() {
        return timeLimitMs;
    }

    public void setTimeLimitMs(Integer timeLimitMs) {
        this.timeLimitMs = timeLimitMs;
    }

    public Integer getMemoryLimitMb() {
        return memoryLimitMb;
    }

    public void setMemoryLimitMb(Integer memoryLimitMb) {
        this.memoryLimitMb = memoryLimitMb;
    }

    public Integer getOutputLimitKb() {
        return outputLimitKb;
    }

    public void setOutputLimitKb(Integer outputLimitKb) {
        this.outputLimitKb = outputLimitKb;
    }
}
