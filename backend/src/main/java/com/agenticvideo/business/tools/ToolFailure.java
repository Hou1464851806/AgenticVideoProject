package com.agenticvideo.business.tools;

public class ToolFailure extends RuntimeException {
    public final String code,category;
    public ToolFailure(String code,String category,String message){super(message);this.code=code;this.category=category;}
}
