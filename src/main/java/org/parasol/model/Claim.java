package org.parasol.model;

import java.time.LocalDate;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import jakarta.persistence.Temporal;
import jakarta.persistence.TemporalType;

import io.quarkus.hibernate.orm.panache.PanacheEntity;

import com.fasterxml.jackson.databind.PropertyNamingStrategies.SnakeCaseStrategy;
import com.fasterxml.jackson.databind.annotation.JsonNaming;

@Entity
@Table(name = "claims")
@JsonNaming(SnakeCaseStrategy.class)
public class Claim extends PanacheEntity {
    public String claimNumber;
    public String category;
    public String policyNumber;

    @Column(columnDefinition="DATETIME")
    @Temporal(TemporalType.DATE)
    public LocalDate inceptionDate;
    public String clientName;
    public String subject;

    @Column(length = 5000)
    public String body;

    @Column(length = 5000)
    public String summary;

    public String location;

    @Column(name = "claim_time")
    public String time;

    @Column(length = 5000)
    public String sentiment;
    public String emailAddress;
    public String status;
}
