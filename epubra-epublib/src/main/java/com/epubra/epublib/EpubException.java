package com.epubra.epublib;

/**
 * EPUB 读取或写出过程中出现的结构性错误。
 */
public class EpubException extends RuntimeException {

    public EpubException(String message) {
        super(message);
    }

    public EpubException(String message, Throwable cause) {
        super(message, cause);
    }
}
