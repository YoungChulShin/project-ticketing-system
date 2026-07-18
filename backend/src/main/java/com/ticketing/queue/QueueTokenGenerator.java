package com.ticketing.queue;

import java.util.UUID;

public class QueueTokenGenerator {

  public static String generate() {
    return UUID.randomUUID().toString();
  }
}
