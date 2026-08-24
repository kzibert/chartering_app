package com.chartering.audit;

import com.chartering.model.DataChange;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * What the revert will and will not touch. Each refusal here is a decision rather than a
 * gap, so each has a test that says which decision it is.
 */
class RevertSupportTest {

    @Test
    void aFieldUpdateCanBePutBack() {
        assertThat(RevertSupport.blockedReason(update("contact", "working", "true", "false")))
                .isNull();
    }

    @Test
    void clearingAFieldCanBePutBack() {
        // oldValue null is a real previous value — the field was empty — not a missing one.
        assertThat(RevertSupport.blockedReason(update("person", "greetingName", null, "Tom")))
                .isNull();
    }

    @Test
    void anAssociationCanBePutBackBecauseItIsStoredAsAnId() {
        assertThat(RevertSupport.blockedReason(update("contact", "person", "12", "13"))).isNull();
    }

    @Test
    void aCreateIsNotRevertedBecauseUndoingItWouldBeACascadingDelete() {
        DataChange create = new DataChange();
        create.setEntityType("company");
        create.setOperation("create");
        create.setNewValue("{\"id\":\"1\"}");

        assertThat(RevertSupport.blockedReason(create)).contains("single field");
    }

    @Test
    void aDeleteIsNotRevertedEvenThoughTheSnapshotHoldsEverything() {
        DataChange delete = new DataChange();
        delete.setEntityType("contact");
        delete.setOperation("delete");
        delete.setOldValue("{\"id\":\"7\",\"contactValue\":\"a@b.com\"}");

        assertThat(RevertSupport.blockedReason(delete)).contains("restored by hand");
    }

    @Test
    void theIdIsNeverMoved() {
        assertThat(RevertSupport.blockedReason(update("company", "id", "1", "2")))
                .contains("identifies the record");
    }

    @Test
    void aFieldThatNoLongerExistsIsRefusedRatherThanGuessedAt() {
        assertThat(RevertSupport.blockedReason(update("company", "faxNumber", "a", "b")))
                .contains("no longer exists");
    }

    @Test
    void anUnknownRecordTypeIsRefused() {
        assertThat(RevertSupport.blockedReason(update("aardvark", "name", "a", "b")))
                .contains("no longer one the application knows about");
    }

    @Test
    void convertsStoredTextBackToTheFieldsOwnType() {
        assertThat(RevertSupport.convert("false", boolean.class)).isEqualTo(false);
        assertThat(RevertSupport.convert("42", Long.class)).isEqualTo(42L);
        assertThat(RevertSupport.convert("hello", String.class)).isEqualTo("hello");
        assertThat(RevertSupport.convert(null, String.class)).isNull();
    }

    @Test
    void handsAnAssociationBackAsAnIdForTheCallerToLoad() {
        assertThat(RevertSupport.convert("12", com.chartering.model.Person.class)).isEqualTo(12L);
    }

    private static DataChange update(String entityType, String field, String oldValue, String newValue) {
        DataChange change = new DataChange();
        change.setEntityType(entityType);
        change.setOperation("update");
        change.setFieldName(field);
        change.setOldValue(oldValue);
        change.setNewValue(newValue);
        return change;
    }
}
