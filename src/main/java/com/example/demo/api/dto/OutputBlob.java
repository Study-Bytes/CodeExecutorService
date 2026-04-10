package com.example.demo.api.dto;

public class OutputBlob {

    private String data;
    private boolean truncated;

    public OutputBlob() {
    }

    public OutputBlob(String data, boolean truncated) {
        this.data = data;
        this.truncated = truncated;
    }

    public String getData() {
        return data;
    }

    public void setData(String data) {
        this.data = data;
    }

    public boolean isTruncated() {
        return truncated;
    }

    public void setTruncated(boolean truncated) {
        this.truncated = truncated;
    }
}
