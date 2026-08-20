package com.chartering.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * The Settings tab's "Use Brevo for circs" checkbox.
 *
 * <p>A boolean rather than the provider name, because that is what the screen offers: one
 * tick box over the flow that already existed. If a third provider ever appears this becomes
 * a named choice — until then, naming it {@code provider} in the API while the UI shows a
 * checkbox would only invite the two to drift apart.
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class CirculationProviderRequest {

    /** True sends circulars through the Brevo transactional API; false, through the mailbox over SMTP. */
    private boolean useBrevo;
}
