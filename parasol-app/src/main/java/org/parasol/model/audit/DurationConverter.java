package org.parasol.model.audit;

import java.time.Duration;

import jakarta.persistence.AttributeConverter;
import jakarta.persistence.Converter;

import io.quarkus.logging.Log;

@Converter(autoApply = true)
public class DurationConverter implements AttributeConverter<Duration, Long> {
    @Override
    public Long convertToDatabaseColumn(Duration duration) {
        var db = (duration == null) ? null : duration.toNanos();
				Log.debugf("Converting duration %s to nanos: %d", duration, db);

				return db;
    }

    @Override
    public Duration convertToEntityAttribute(Long dbData) {
        var duration = (dbData == null) ? null : Duration.ofNanos(dbData);
				Log.debugf("Converting nanos %d to duration: %s", dbData, duration);

				return duration;
    }
}
