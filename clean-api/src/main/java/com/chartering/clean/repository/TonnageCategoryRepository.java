package com.chartering.clean.repository;

import com.chartering.clean.model.TonnageCategory;
import org.springframework.data.jpa.repository.JpaRepository;

public interface TonnageCategoryRepository extends JpaRepository<TonnageCategory, Long> {
}
