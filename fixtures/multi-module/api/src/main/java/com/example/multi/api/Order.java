package com.example.multi.api;

import com.fasterxml.jackson.annotation.JsonProperty;

public record Order(@JsonProperty("id") String id, @JsonProperty("quantity") int quantity) {}
