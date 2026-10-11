package com.agenticvideo.business.conversation;

final class ModelRecordTooLarge extends RuntimeException {
    final String kind;
    final long actualBytes;
    final long limitBytes;

    ModelRecordTooLarge(String kind, long actualBytes, long limitBytes) {
        this.kind = kind;
        this.actualBytes = actualBytes;
        this.limitBytes = limitBytes;
    }
}
