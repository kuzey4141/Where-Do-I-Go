package com.wheredoigo.passport;

public enum PassportType {
    ORDINARY("Umuma Mahsus Pasaport", "Bordo"),
    SPECIAL("Hususi Pasaport", "Yeşil"),
    SERVICE("Hizmet Pasaportu", "Gri"),
    DIPLOMATIC("Diplomatik Pasaport", "Siyah");

    private final String displayName;
    private final String color;

    PassportType(String displayName, String color) {
        this.displayName = displayName;
        this.color = color;
    }

    public String displayName() {
        return displayName;
    }

    public String color() {
        return color;
    }
}
