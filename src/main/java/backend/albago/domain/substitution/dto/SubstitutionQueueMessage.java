package backend.albago.domain.substitution.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import java.time.LocalDateTime;

@Getter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class SubstitutionQueueMessage {
    private Long teamId;
    private Long requesterId;
    private Long substituteId;
    private LocalDateTime timeRangeStart;
    private LocalDateTime timeRangeEnd;
}