package com.ticketing.sse;

public enum EventType {

  ADMISSION("admission"),
  EXPIRED("expired")
  ;


  private final String value;

  EventType(String value) {
    this.value = value;
  }

  public String getValue() {
    return value;
  }

  @Override
  public String toString() {
    return this.value;
  }
}
