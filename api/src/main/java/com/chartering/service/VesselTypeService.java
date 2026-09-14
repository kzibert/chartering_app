package com.chartering.service;

import com.chartering.audit.ChangeContext;
import com.chartering.dto.VesselTypeCheckResponse;
import com.chartering.dto.VesselTypeCheckResponse.Remap;
import com.chartering.dto.VesselTypeCheckResponse.Unmapped;
import com.chartering.model.Vessel;
import com.chartering.repository.VesselRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.List;

/**
 * The fleet's vessel types put back onto the list: every one-off wording a circular wrote into
 * the column, mapped to the category it names.
 *
 * <p>Same arrangement as {@link VesselCapacityService}: a dry run, then one transaction under one
 * named change set, so the History tab shows the operation and any one vessel's type can be
 * reverted there. <b>The wording is not thrown away</b> — "GENERAL CARGO / BOX / DOUBLE SKINNED"
 * says more than its category does — so it is added to the vessel's notes as "Type as written".
 * A wording that names no category is reported and left as it is. Safe to repeat: a remapped
 * type is a category and is not looked at again.
 */
@Service
@RequiredArgsConstructor
public class VesselTypeService {

    public static final String CHANGE_SET = "Vessel types: one-off wordings mapped to the type list";

    private final VesselRepository vessels;

    @Transactional(readOnly = true)
    public VesselTypeCheckResponse check() {
        return run(false);
    }

    @Transactional
    public VesselTypeCheckResponse fix() {
        ChangeContext.describe(CHANGE_SET);
        return run(true);
    }

    private VesselTypeCheckResponse run(boolean apply) {
        List<Remap> remapped = new ArrayList<>();
        List<Unmapped> unmapped = new ArrayList<>();
        for (Vessel v : vessels.findAll(Sort.by("name"))) {
            String type = v.getVesselType();
            if (type == null || type.isBlank() || VesselTypes.isCanonical(type)) continue;
            String category = VesselTypes.canonical(type);
            if (category == null) {
                unmapped.add(new Unmapped(v.getId(), v.getName(), type));
                continue;
            }
            remapped.add(new Remap(v.getId(), v.getName(), type, category));
            if (apply) {
                // A case variant of a category ("general cargo") says nothing the category
                // does not, so only a real wording is carried into the notes.
                if (!category.equalsIgnoreCase(type.strip())) {
                    String note = "Type as written: " + type.strip();
                    String notes = v.getNotes();
                    if (notes == null || notes.isBlank()) {
                        v.setNotes(note);
                    } else if (!notes.contains(note)) {
                        v.setNotes(notes + "\n" + note);
                    }
                }
                v.setVesselType(category);
            }
        }
        return new VesselTypeCheckResponse(apply, apply ? CHANGE_SET : null, remapped, unmapped);
    }
}
