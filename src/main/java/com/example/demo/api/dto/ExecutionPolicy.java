package com.example.demo.api.dto;

import com.fasterxml.jackson.annotation.JsonSetter;
import com.fasterxml.jackson.annotation.Nulls;

public class ExecutionPolicy {

    @JsonSetter(nulls = Nulls.SKIP)
    private Boolean networkDisabled = true;

    @JsonSetter(nulls = Nulls.SKIP)
    private Boolean readOnlyFs = true;

    public Boolean getNetworkDisabled() {
        return networkDisabled;
    }

    public void setNetworkDisabled(Boolean networkDisabled) {
        this.networkDisabled = networkDisabled;
    }

    public Boolean getReadOnlyFs() {
        return readOnlyFs;
    }

    public void setReadOnlyFs(Boolean readOnlyFs) {
        this.readOnlyFs = readOnlyFs;
    }
}
