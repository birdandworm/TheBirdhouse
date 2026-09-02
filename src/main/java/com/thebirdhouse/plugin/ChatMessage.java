package com.thebirdhouse.plugin;

import lombok.Data;

/**
 * One team chat message as the backend hands it over.
 *
 * Deliberately thinner than what the website stores: no colour (that is a CSS
 * variable and means nothing in Swing) and no image URL, only a flag saying an
 * image was posted. The panel never fetches chat images, so a conversation full
 * of screenshots costs the same as a conversation full of text.
 */
@Data
public class ChatMessage {
    private String id;
    private String name;
    private String text;
    private boolean hasImage;
    private long timestamp;
}
