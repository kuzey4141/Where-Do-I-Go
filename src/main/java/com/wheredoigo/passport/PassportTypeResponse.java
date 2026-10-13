package com.wheredoigo.passport;

public record PassportTypeResponse(PassportType code, String name, String color) {
    public static PassportTypeResponse from(PassportType type) {
        return new PassportTypeResponse(type, type.displayName(), type.color());
    }
}
