package edu.gatech.sip6321.social;

import java.util.List;

public record SocialPage<T>(List<T> items, int page, int size, boolean hasNext) {}
