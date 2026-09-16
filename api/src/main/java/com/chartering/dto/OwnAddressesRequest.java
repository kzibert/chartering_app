package com.chartering.dto;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/** The desk's own email addresses, as set from the Settings tab. The whole list, replaced. */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class OwnAddressesRequest {

    /** Empty clears the list. */
    @NotNull(message = "addresses is required; send an empty list to clear it")
    @Size(max = 50, message = "no more than 50 addresses")
    private List<String> addresses;
}
