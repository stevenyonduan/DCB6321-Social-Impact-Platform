package edu.gatech.sip6321.registration;

import java.util.List;

public record RegistrationPage<T>(List<T> items, int page, int size, boolean hasNext) {
    public RegistrationPage {
        items = List.copyOf(items);
    }
}
