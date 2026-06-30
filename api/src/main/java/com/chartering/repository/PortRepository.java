package com.chartering.repository;

import com.chartering.model.Port;
import org.springframework.data.jpa.repository.JpaRepository;

public interface PortRepository extends JpaRepository<Port, Long> {
}
