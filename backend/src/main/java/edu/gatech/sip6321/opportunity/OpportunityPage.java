package edu.gatech.sip6321.opportunity;

import java.util.List;

public record OpportunityPage(
        List<OpportunityResponse> items, int page, int size, boolean hasNext) {
    public OpportunityPage {
        items = List.copyOf(items);
    }
}
