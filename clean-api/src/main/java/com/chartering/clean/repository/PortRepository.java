package com.chartering.clean.repository;

import com.chartering.clean.model.Port;
import org.springframework.data.jpa.repository.JpaRepository;

public interface PortRepository extends JpaRepository<Port, Long> {
}
