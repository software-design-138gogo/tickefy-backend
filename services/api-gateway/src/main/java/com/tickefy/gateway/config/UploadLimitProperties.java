package com.tickefy.gateway.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.util.unit.DataSize;

@ConfigurationProperties(prefix = "app.gateway.upload")
public class UploadLimitProperties {

  private DataSize aiBioMaxRequestSize = DataSize.ofMegabytes(30);

  private DataSize csvMaxRequestSize = DataSize.ofMegabytes(12);

  public DataSize getAiBioMaxRequestSize() {
    return aiBioMaxRequestSize;
  }

  public void setAiBioMaxRequestSize(
      DataSize aiBioMaxRequestSize) {
    this.aiBioMaxRequestSize = aiBioMaxRequestSize;
  }

  public DataSize getCsvMaxRequestSize() {
    return csvMaxRequestSize;
  }

  public void setCsvMaxRequestSize(
      DataSize csvMaxRequestSize) {
    this.csvMaxRequestSize = csvMaxRequestSize;
  }
}