package com.panchayat.backend.chat.entity;

public enum ChatMessageType {
    TEXT,
    IMAGE,
    /** From Sarpanch, the official account (read-only for users). */
    SYSTEM
}