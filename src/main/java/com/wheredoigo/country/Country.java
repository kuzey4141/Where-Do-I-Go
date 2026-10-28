package com.wheredoigo.country;

public record Country(String isoCode, String name, String summary) {
    public Country(String isoCode, String name) {
        this(isoCode, name, null);
    }
}
