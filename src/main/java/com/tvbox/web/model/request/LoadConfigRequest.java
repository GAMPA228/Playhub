package com.tvbox.web.model.request;

import jakarta.validation.constraints.NotBlank;

public class LoadConfigRequest {

    @NotBlank
    private String url;
    private boolean persist = true;

    public String getUrl() {
        return url;
    }

    public void setUrl(String url) {
        this.url = url;
    }

    public boolean isPersist() {
        return persist;
    }

    public void setPersist(boolean persist) {
        this.persist = persist;
    }
}
