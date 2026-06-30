package com.chartering.repository;

import com.chartering.model.TonnageCategory;
import org.springframework.data.jpa.repository.JpaRepository;

public interface TonnageCategoryRepository extends JpaRepository<TonnageCategory, Long> {
}
