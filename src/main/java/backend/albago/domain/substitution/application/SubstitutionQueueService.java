package backend.albago.domain.substitution.application;

import backend.albago.domain.member.domain.entity.Member;
import backend.albago.domain.member.domain.repository.MemberRepository;
import backend.albago.domain.model.enums.RequestStatus;
import backend.albago.domain.substitution.domain.entity.SubstitutionRequest;
import backend.albago.domain.substitution.domain.repository.SubstitutionRequestRepository;
import backend.albago.domain.substitution.dto.SubstitutionQueueMessage;
import backend.albago.domain.team.domain.entity.Team;
import backend.albago.domain.team.domain.repository.TeamRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Slf4j
@Service
@RequiredArgsConstructor
public class SubstitutionQueueService {

    private final RedisTemplate<String, Object> redisTemplate;
    private final ObjectMapper objectMapper;

    // DB 저장을 위한 Repository 의존성 주입 (순환 참조 방지)
    private final SubstitutionRequestRepository substitutionRequestRepository;
    private final TeamRepository teamRepository;
    private final MemberRepository memberRepository;

    private static final String QUEUE_KEY = "substitution:queue";

    // 1. 대타 요청을 Redis Queue에 넣는 메서드 (Producer)
    public void enqueueSubstitutionRequest(SubstitutionQueueMessage message) {
        redisTemplate.opsForList().rightPush(QUEUE_KEY, message);
        log.info("Redis Queue에 대타 요청 적재 완료: requesterId={}", message.getRequesterId());
    }

    // 2. 1초마다 Queue에서 꺼내서 DB에 순차적으로 저장하는 워커 (Consumer)
    @Scheduled(fixedDelay = 1000)
    @Transactional // 영속성 컨텍스트 유지를 위해 트랜잭션 적용
    public void processSubstitutionQueue() {
        Object item = redisTemplate.opsForList().leftPop(QUEUE_KEY);

        if (item != null) {
            SubstitutionQueueMessage message = objectMapper.convertValue(item, SubstitutionQueueMessage.class);
            log.info("Redis Queue에서 대타 요청 처리 시작: requesterId={}", message.getRequesterId());

            try {
                // 1. 연관된 엔티티(Team, Member) 조회
                Team team = teamRepository.findById(message.getTeamId())
                        .orElseThrow(() -> new IllegalArgumentException("Team not found"));

                Member requester = memberRepository.findById(message.getRequesterId())
                        .orElseThrow(() -> new IllegalArgumentException("Requester not found"));

                Member substitute = null;
                if (message.getSubstituteId() != null) {
                    substitute = memberRepository.findById(message.getSubstituteId())
                            .orElse(null);
                }

                // 2. 새로운 대타 요청 엔티티 생성
                SubstitutionRequest newRequest = SubstitutionRequest.builder()
                        .team(team)
                        .requester(requester)
                        .substitute(substitute)
                        .timeRangeStart(message.getTimeRangeStart())
                        .timeRangeEnd(message.getTimeRangeEnd())
                        .status(RequestStatus.PENDING)
                        .build();

                // 3. DB에 안전하게 단일 Insert (병목/동시성 문제 해결)
                substitutionRequestRepository.save(newRequest);

                log.info("Redis Queue 대타 요청 DB 저장 완료: requestId={}", newRequest.getId());

            } catch (Exception e) {
                log.error("Queue 처리 중 DB 저장 실패. requesterId={}", message.getRequesterId(), e);
            }
        }
    }
}