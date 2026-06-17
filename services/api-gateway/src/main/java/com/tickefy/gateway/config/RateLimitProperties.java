package com.tickefy.gateway.config;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

@Validated
@ConfigurationProperties(prefix = "app.rate-limit")
public class RateLimitProperties {

  private boolean enabled = true;

  /**
   * This feature is only enabled when the Gateway is behind a trusted reverse
   * proxy/load balancer and clients cannot directly access the Gateway.
   */
  private boolean trustForwardedHeaders = false;

  @Min(1)
  private int retryAfterSeconds = 1;

  @Valid
  private Policy defaultPolicy = new Policy(20, 40, 1);

  @Valid
  private Policy authPolicy = new Policy(5, 10, 1);

  @Valid
  private Policy purchasePolicy = new Policy(2, 4, 1);

  public boolean isEnabled() {
    return enabled;
  }

  public void setEnabled(boolean enabled) {
    this.enabled = enabled;
  }

  public boolean isTrustForwardedHeaders() {
    return trustForwardedHeaders;
  }

  public void setTrustForwardedHeaders(
      boolean trustForwardedHeaders) {
    this.trustForwardedHeaders = trustForwardedHeaders;
  }

  public int getRetryAfterSeconds() {
    return retryAfterSeconds;
  }

  public void setRetryAfterSeconds(
      int retryAfterSeconds) {
    this.retryAfterSeconds = retryAfterSeconds;
  }

  public Policy getDefaultPolicy() {
    return defaultPolicy;
  }

  public void setDefaultPolicy(Policy defaultPolicy) {
    this.defaultPolicy = defaultPolicy;
  }

  public Policy getAuthPolicy() {
    return authPolicy;
  }

  public void setAuthPolicy(Policy authPolicy) {
    this.authPolicy = authPolicy;
  }

  public Policy getPurchasePolicy() {
    return purchasePolicy;
  }

  public void setPurchasePolicy(Policy purchasePolicy) {
    this.purchasePolicy = purchasePolicy;
  }

  public static class Policy {

    @Min(1)
    private int replenishRate;

    @Min(1)
    private int burstCapacity;

    @Min(1)
    private int requestedTokens;

    public Policy() {
    }

    public Policy(
        int replenishRate,
        int burstCapacity,
        int requestedTokens) {
      this.replenishRate = replenishRate;
      this.burstCapacity = burstCapacity;
      this.requestedTokens = requestedTokens;
    }

    public int getReplenishRate() {
      return replenishRate;
    }

    public void setReplenishRate(int replenishRate) {
      this.replenishRate = replenishRate;
    }

    public int getBurstCapacity() {
      return burstCapacity;
    }

    public void setBurstCapacity(int burstCapacity) {
      this.burstCapacity = burstCapacity;
    }

    public int getRequestedTokens() {
      return requestedTokens;
    }

    public void setRequestedTokens(int requestedTokens) {
      this.requestedTokens = requestedTokens;
    }
  }
}