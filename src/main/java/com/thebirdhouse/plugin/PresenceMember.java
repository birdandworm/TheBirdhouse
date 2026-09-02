package com.thebirdhouse.plugin;

import lombok.Data;

/**
 * One teammate's status as the backend hands it over.
 *
 * A boxed world, because null is a real answer and 0 is not a world: it means either
 * offline or not sharing, and the panel needs to tell those apart. The server never
 * sends a world for someone who has not opted in, so nothing has to be hidden here.
 */
@Data
public class PresenceMember {
    private String name;
    private boolean self;
    private boolean sharing;
    private boolean online;
    private Integer world;
    private long lastSeen;
}
