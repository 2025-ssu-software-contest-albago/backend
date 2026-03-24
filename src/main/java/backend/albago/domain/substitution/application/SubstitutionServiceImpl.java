package backend.albago.domain.substitution.application;

import backend.albago.domain.member.domain.entity.Member;
import backend.albago.domain.member.domain.repository.MemberRepository;
import backend.albago.domain.model.enums.RequestStatus;
import backend.albago.domain.schedule.domain.entity.PersonalSchedule;
import backend.albago.domain.schedule.domain.entity.TeamSchedule;
import backend.albago.domain.schedule.domain.repository.PersonalScheduleRepository;
import backend.albago.domain.schedule.domain.repository.TeamScheduleRepository;
import backend.albago.domain.substitution.converter.SubstitutionConverter;
import backend.albago.domain.substitution.domain.entity.SubstitutionRequest;
import backend.albago.domain.substitution.domain.repository.SubstitutionRequestRepository;
import backend.albago.domain.substitution.dto.SubstitutionQueueMessage;
import backend.albago.domain.substitution.dto.SubstitutionRequestDTO;
import backend.albago.domain.substitution.dto.SubstitutionResponseDTO;
import backend.albago.domain.substitution.exception.SubstitutionException;
import backend.albago.domain.team.domain.entity.Team;
import backend.albago.domain.team.domain.entity.TeamMember;
import backend.albago.domain.team.domain.repository.TeamMemberRepository;
import backend.albago.domain.team.domain.repository.TeamRepository;
import backend.albago.global.error.code.status.ErrorStatus;
import backend.albago.global.exception.GeneralException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
@Slf4j
public class SubstitutionServiceImpl implements SubstitutionService {

    private final SubstitutionRequestRepository substitutionRequestRepository;
    private final MemberRepository memberRepository;
    private final TeamRepository teamRepository;
    private final TeamMemberRepository teamMemberRepository;
    private final PersonalScheduleRepository personalScheduleRepository;
    private final TeamScheduleRepository teamScheduleRepository;
    private final SubstitutionQueueService substitutionQueueService;

    @Override
    @Transactional
    public SubstitutionResponseDTO.CreateSubstitutionResult createSubstitution(Long teamId, SubstitutionRequestDTO.CreateSubstitutionDTO requestDTO, String memberIdString) {

        Member requester = validateMemberAndTeam(memberIdString, teamId);

        Team team = teamRepository.findById(teamId)
                .orElseThrow(() -> new GeneralException(ErrorStatus.TEAM_NOT_FOUND));

        Member substitute = null;
        if (requestDTO.getSubstituteId() != null) {
            substitute = memberRepository.findById(requestDTO.getSubstituteId())
                    .orElseThrow(() -> new GeneralException(ErrorStatus.NO_SUCH_MEMBER));

            if (requester.getId().equals(substitute.getId())) {
                throw new SubstitutionException(ErrorStatus.CANNOT_REQUEST_SELF_SUBSTITUTION);
            }

            Optional<TeamMember> subTeamMember = teamMemberRepository.findByMemberAndTeam(substitute, team);
            if (subTeamMember.isEmpty()) {
                throw new SubstitutionException(ErrorStatus.SUBSTITUTE_NOT_IN_TEAM);
            }
        }

        if (requestDTO.getTimeRangeStart().isAfter(requestDTO.getTimeRangeEnd()) ||
                requestDTO.getTimeRangeStart().isBefore(LocalDateTime.now())) {
            throw new SubstitutionException(ErrorStatus.INVALID_SUBSTITUTION_TIME);
        }

        // 1. DB에 직접 저장하지 않고 Queue에 넣을 메시지 생성
        SubstitutionQueueMessage queueMessage = SubstitutionQueueMessage.builder()
                .teamId(teamId)
                .requesterId(requester.getId())
                .substituteId(substitute != null ? substitute.getId() : null)
                .timeRangeStart(requestDTO.getTimeRangeStart())
                .timeRangeEnd(requestDTO.getTimeRangeEnd())
                .build();

        // 2. Redis Queue로 데이터 비동기 적재 (여기서 빠른 응답 반환)
        substitutionQueueService.enqueueSubstitutionRequest(queueMessage);

        // 3. 비동기 처리이므로 실제 DB PK(id)는 아직 없음. 클라이언트에게 임시 응답을 위한 가짜 엔티티 생성
        SubstitutionRequest tempRequest = SubstitutionRequest.builder()
                .id(-1L) // 비동기 접수 상태를 나타내는 임시 ID (프론트에서 -1이면 '처리중'으로 인지하도록 구성)
                .team(team)
                .requester(requester)
                .substitute(substitute)
                .timeRangeStart(requestDTO.getTimeRangeStart())
                .timeRangeEnd(requestDTO.getTimeRangeEnd())
                .status(RequestStatus.PENDING)
                .build();

        return SubstitutionConverter.toCreateSubstitutionResult(tempRequest);
    }

    @Override
    @Transactional(readOnly = true)
    public SubstitutionResponseDTO.FindRequestSubstitutionResult findRequestSubstitution(Long teamId, String memberIdString) {

        Member requester = validateMemberAndTeam(memberIdString, teamId);

        Team team = teamRepository.findById(teamId)
                .orElseThrow(() -> new GeneralException(ErrorStatus.TEAM_NOT_FOUND));

        List<SubstitutionRequest> requests = substitutionRequestRepository.findByTeamAndRequesterOrderByCreatedAtDesc(team, requester);

        return SubstitutionConverter.toFindRequestSubstitutionResult(requests);
    }

    @Override
    @Transactional(readOnly = true)
    public SubstitutionResponseDTO.FindReceiveSubstitutionResult findReceiveSubstitution(Long teamId, String memberIdString) {

        Member receiver = validateMemberAndTeam(memberIdString, teamId);

        Team team = teamRepository.findById(teamId)
                .orElseThrow(() -> new GeneralException(ErrorStatus.TEAM_NOT_FOUND));

        List<SubstitutionRequest> requests = substitutionRequestRepository.findByTeamAndSubstituteOrderByCreatedAtDesc(team, receiver);

        return SubstitutionConverter.toFindReceiveSubstitutionResult(requests);
    }

    @Override
    @Transactional(readOnly = true)
    public SubstitutionResponseDTO.FindSubstitutionResult findSubstitution(Long teamId, Long requestId, String memberIdString) {

        Member currentMember = validateMemberAndTeam(memberIdString, teamId);

        SubstitutionRequest request = findSubstitutionAndValidateAccess(teamId, requestId, currentMember);

        return SubstitutionConverter.toFindSubstitutionResult(request);
    }

    @Override
    @Transactional
    public SubstitutionResponseDTO.AcceptSubstitutionResult acceptSubstitution(Long teamId, Long requestId, String memberIdString) {

        Member substituteMember = validateMemberAndTeam(memberIdString, teamId);

        Team team = teamRepository.findById(teamId)
                .orElseThrow(() -> new GeneralException(ErrorStatus.TEAM_NOT_FOUND));

        SubstitutionRequest acceptedRequest = substitutionRequestRepository.findByTeamAndId(team, requestId)
                .orElseThrow(() -> new SubstitutionException(ErrorStatus.SUBSTITUTION_NOT_FOUND));

        if (acceptedRequest.getSubstitute() != null && !acceptedRequest.getSubstitute().getId().equals(substituteMember.getId())) {
            throw new SubstitutionException(ErrorStatus.NOT_SUBSTITUTION_SUBSTITUTE);
        }

        if (acceptedRequest.getStatus() != RequestStatus.PENDING) {
            throw new SubstitutionException(ErrorStatus.SUBSTITUTION_NOT_PENDING);
        }

        acceptedRequest.setStatus(RequestStatus.ACCEPTED);
        acceptedRequest.setSubstitute(substituteMember);

        LocalDateTime requestStart = acceptedRequest.getTimeRangeStart();
        LocalDateTime requestEnd = acceptedRequest.getTimeRangeEnd();
        LocalDate requestDate = requestStart.toLocalDate(); // 날짜 부분 추출

        // 1. 겹치는 대타 요청 취소
        List<SubstitutionRequest> requesterOverlappingPendingRequests = substitutionRequestRepository.findPendingOverlappingRequestsForMember(
                team, acceptedRequest.getRequester(), requestStart, requestEnd
        );
        List<Long> requesterRequestIdsToCancel = requesterOverlappingPendingRequests.stream()
                .filter(req -> !req.getId().equals(acceptedRequest.getId()))
                .map(SubstitutionRequest::getId)
                .collect(Collectors.toList());

        if (!requesterRequestIdsToCancel.isEmpty()) {
            substitutionRequestRepository.updateStatusToCanceledByIdIn(requesterRequestIdsToCancel);
            log.info("Requester {}'s overlapping pending substitution requests (IDs: {}) canceled.", acceptedRequest.getRequester().getId(), requesterRequestIdsToCancel);
        }

        List<SubstitutionRequest> substituteOverlappingPendingRequests = substitutionRequestRepository.findPendingOverlappingRequestsForMember(
                team, substituteMember, requestStart, requestEnd
        );
        List<Long> substituteRequestIdsToCancel = substituteOverlappingPendingRequests.stream()
                .filter(req -> !req.getId().equals(acceptedRequest.getId()))
                .map(SubstitutionRequest::getId)
                .collect(Collectors.toList());

        if (!substituteRequestIdsToCancel.isEmpty()) {
            substitutionRequestRepository.updateStatusToCanceledByIdIn(substituteRequestIdsToCancel);
            log.info("Substitute {}'s overlapping pending substitution requests (IDs: {}) canceled.", substituteMember.getId(), substituteRequestIdsToCancel);
        }

        // 2. 원본 팀 스케줄 찾기 (요청자가 원래 근무하기로 되어있던 스케줄)
        List<TeamSchedule> originalRequesterTeamSchedules = teamScheduleRepository.findOverlappingSchedulesForTeamMember(
                team, requestDate, acceptedRequest.getRequester(), requestStart, requestEnd
        );

        // 원본 스케줄 중 하나를 기준으로 급여 정보를 가져옴 (여러 개 겹칠 경우 가장 적절한 것을 선택하는 로직 필요)
        TeamSchedule originalTeamSchedule = originalRequesterTeamSchedules.stream().findFirst().orElse(null);

        // 3. 개인 시간표 반영 (기존 스케줄 조정 및 새 스케줄 생성)
        // 3-1. 요청자 (원래 근무자)의 개인 시간표에서 해당 근무를 삭제하거나 변경
        List<PersonalSchedule> requesterConflictingPersonalSchedules = personalScheduleRepository.findOverlappingSchedulesForMember(
                acceptedRequest.getRequester(), requestDate, requestStart, requestEnd
        );
        if (!requesterConflictingPersonalSchedules.isEmpty()) {
            personalScheduleRepository.deleteAll(requesterConflictingPersonalSchedules);
            log.info("Requester {}'s original conflicting personal schedules deleted for substitution request ID {}.", acceptedRequest.getRequester().getId(), acceptedRequest.getId());
        }

        // 3-2. 대타자 (새로운 근무자)의 개인 시간표에 새로운 근무 스케줄 추가
        PersonalSchedule newSubstitutePersonalSchedule = PersonalSchedule.builder()
                .member(substituteMember)
                .team(team)
                .scheduleType("team")
                .startTime(requestStart)
                .endTime(requestEnd)
                .name("대타 근무 (요청자: " + acceptedRequest.getRequester().getName() + ")")
                .memo("대타 요청 ID: " + acceptedRequest.getId())
                .color(acceptedRequest.getTeam().getColor())
                .relatedTeamScheduleId(originalTeamSchedule != null ? originalTeamSchedule.getId() : null)

                // 급여 정보 복사
                .hourlyWage(originalTeamSchedule != null ? originalTeamSchedule.getScheduleHourlyWage() : null)
                .weeklyAllowance(originalTeamSchedule != null ? originalTeamSchedule.getWeeklyAllowance() : null)
                .nightAllowance(originalTeamSchedule != null ? originalTeamSchedule.getNightAllowance() : null)
                .nightRate(originalTeamSchedule != null ? originalTeamSchedule.getNightRate() : null)
                .overtimeAllowance(originalTeamSchedule != null ? originalTeamSchedule.getOvertimeAllowance() : null)
                .overtimeRate(originalTeamSchedule != null ? originalTeamSchedule.getOvertimeRate() : null)
                .holidayAllowance(originalTeamSchedule != null ? originalTeamSchedule.getHolidayAllowance() : null)
                .holidayRate(originalTeamSchedule != null ? originalTeamSchedule.getHolidayRate() : null)
                .deductions(originalTeamSchedule != null ? originalTeamSchedule.getDeductions() : null)
                .build();
        personalScheduleRepository.save(newSubstitutePersonalSchedule);
        log.info("Substitute {}'s new personal schedule created for substitution request ID {}.", substituteMember.getId(), acceptedRequest.getId());


        // 4. 팀 시간표 반영 (원래 근무자 스케줄 변경 또는 삭제, 새 근무자 스케줄 생성)
        // 4-1. 요청자 (원래 근무자)의 팀 스케줄에서 해당 근무를 삭제하거나 변경
        if (!originalRequesterTeamSchedules.isEmpty()) {
            teamScheduleRepository.deleteAll(originalRequesterTeamSchedules); // 삭제
            log.info("Requester {}'s original conflicting team schedules deleted for substitution request ID {}.", acceptedRequest.getRequester().getId(), acceptedRequest.getId());
        }

        // 4-2. 대타자 (새로운 근무자)의 팀 시간표에 새로운 근무 스케줄 추가
        TeamSchedule newSubstituteTeamSchedule = TeamSchedule.builder()
                .team(team)
                .member(substituteMember)
                .startTime(requestStart)
                .endTime(requestEnd)
                .name("대타 근무 (요청자: " + acceptedRequest.getRequester().getName() + ")")
                .memo("대타 요청 ID: " + acceptedRequest.getId())
                .color(acceptedRequest.getTeam().getColor())

                // 급여 정보 복사
                .scheduleHourlyWage(originalTeamSchedule != null ? originalTeamSchedule.getScheduleHourlyWage() : null)
                .weeklyAllowance(originalTeamSchedule != null ? originalTeamSchedule.getWeeklyAllowance() : null)
                .nightAllowance(originalTeamSchedule != null ? originalTeamSchedule.getNightAllowance() : null)
                .nightRate(originalTeamSchedule != null ? originalTeamSchedule.getNightRate() : null)
                .overtimeAllowance(originalTeamSchedule != null ? originalTeamSchedule.getOvertimeAllowance() : null)
                .overtimeRate(originalTeamSchedule != null ? originalTeamSchedule.getOvertimeRate() : null)
                .holidayAllowance(originalTeamSchedule != null ? originalTeamSchedule.getHolidayAllowance() : null)
                .holidayRate(originalTeamSchedule != null ? originalTeamSchedule.getHolidayRate() : null)
                .deductions(originalTeamSchedule != null ? originalTeamSchedule.getDeductions() : null)
                .build();
        teamScheduleRepository.save(newSubstituteTeamSchedule);
        log.info("Substitute {}'s new team schedule created for substitution request ID {}.", substituteMember.getId(), acceptedRequest.getId());


        return SubstitutionConverter.toAcceptSubstitutionResult(acceptedRequest);
    }

    @Override
    @Transactional
    public SubstitutionResponseDTO.RejectSubstitutionResult rejectSubstitution(Long teamId, Long requestId, String memberIdString) {

        Member substitute = validateMemberAndTeam(memberIdString, teamId);

        SubstitutionRequest request = findSubstitutionAndValidateSubstitute(teamId, requestId, substitute);

        if (request.getStatus() != RequestStatus.PENDING) {
            throw new SubstitutionException(ErrorStatus.SUBSTITUTION_NOT_PENDING);
        }

        request.setStatus(RequestStatus.REJECTED);

        substitutionRequestRepository.save(request);

        return SubstitutionConverter.toRejectSubstitutionResult(request);
    }

    @Override
    @Transactional
    public void deleteSubstitution(Long teamId, Long requestId, String memberIdString) {

        Member requester = validateMemberAndTeam(memberIdString, teamId);

        SubstitutionRequest request = findSubstitutionAndValidateRequester(teamId, requestId, requester);

        if (request.getStatus() != RequestStatus.PENDING) {
            throw new SubstitutionException(ErrorStatus.SUBSTITUTION_NOT_PENDING);
        }

        substitutionRequestRepository.delete(request);
    }

    @Override
    @Transactional(readOnly = true)
    public SubstitutionResponseDTO.CheckAvailabilityResult checkAvailability(Long teamId, SubstitutionRequestDTO.CheckAvailabilityDTO requestDTO, String memberIdString) {

        Member requester = validateMemberAndTeam(memberIdString, teamId);

        Team team = teamRepository.findById(teamId)
                .orElseThrow(() -> new GeneralException(ErrorStatus.TEAM_NOT_FOUND));

        // 요청 시간 유효성 검사
        LocalDateTime requestStartDateTime = requestDTO.getTimeRangeStart();
        LocalDateTime requestEndDateTime = requestDTO.getTimeRangeEnd();
        LocalDate requestDate = requestStartDateTime.toLocalDate();

        if (requestStartDateTime.isAfter(requestEndDateTime) || requestStartDateTime.isBefore(LocalDateTime.now())) {
            throw new SubstitutionException(ErrorStatus.INVALID_SUBSTITUTION_TIME);
        }

        List<TeamMember> teamMembers = teamMemberRepository.findByTeam(team);
        List<Member> allTeamMembers = teamMembers.stream()
                .map(TeamMember::getMember)
                .collect(Collectors.toList());

        List<Member> availableMembers = new ArrayList<>();

        // 2. 각 멤버별로 대타 가능 여부를 확인합니다.
        for (Member member : allTeamMembers) {
            // 2-1. 요청자 본인은 대타 가능 목록에서 제외
            if (member.getId().equals(requester.getId())) {
                continue;
            }

            // 2-2. 해당 멤버의 개인 시간표 중 겹치는 것이 있는지 확인
            // PersonalScheduleRepository의 findOverlappingSchedulesForMember 메서드 시그니처 변경에 따라 LocalDateTime으로 전달
            List<PersonalSchedule> conflictingSchedules = personalScheduleRepository.findOverlappingSchedulesForMember(
                    member, requestDate, requestStartDateTime, requestEndDateTime
            );

            // 겹치는 개인 시간표가 없으면 대타 가능 멤버에 추가
            if (conflictingSchedules.isEmpty()) {
                availableMembers.add(member);
            }
        }

        return SubstitutionConverter.toCheckAvailabilityResult(availableMembers);
    }


    // 헬퍼 메서드: 멤버, 팀 존재 여부, 그리고 멤버가 팀에 소속되어 있는지 확인
    private Member validateMemberAndTeam(String memberIdString, Long teamId) {
        Long memberId = Long.parseLong(memberIdString);
        Member member = memberRepository.findById(memberId)
                .orElseThrow(() -> new GeneralException(ErrorStatus.NO_SUCH_MEMBER));

        Team team = teamRepository.findById(teamId)
                .orElseThrow(() -> new GeneralException(ErrorStatus.TEAM_NOT_FOUND));

        Optional<TeamMember> teamMember = teamMemberRepository.findByMemberAndTeam(member, team);
        if (teamMember.isEmpty()) {
            throw new GeneralException(ErrorStatus.MEMBER_NOT_IN_TEAM);
        }

        return member;
    }

    // 헬퍼 메서드: 대타 요청 조회 및 요청 멤버의 권한 검증 (요청자 또는 대타자인지)
    private SubstitutionRequest findSubstitutionAndValidateAccess(Long teamId, Long requestId, Member currentMember) {
        Team team = teamRepository.findById(teamId)
                .orElseThrow(() -> new GeneralException(ErrorStatus.TEAM_NOT_FOUND));

        SubstitutionRequest request = substitutionRequestRepository.findByTeamAndId(team, requestId)
                .orElseThrow(() -> new SubstitutionException(ErrorStatus.SUBSTITUTION_NOT_FOUND));

        if (!request.getRequester().getId().equals(currentMember.getId()) &&
                (request.getSubstitute() == null || !request.getSubstitute().getId().equals(currentMember.getId()))) {
            throw new GeneralException(ErrorStatus.MEMBER_NOT_IN_TEAM); // 또는 더 구체적인 에러 (예: NOT_AUTHORIZED_FOR_REQUEST)
        }
        return request;
    }

    // 헬퍼 메서드: 대타 요청 조회 및 요청자가 맞는지 검증
    private SubstitutionRequest findSubstitutionAndValidateRequester(Long teamId, Long requestId, Member requesterMember) {
        Team team = teamRepository.findById(teamId)
                .orElseThrow(() -> new GeneralException(ErrorStatus.TEAM_NOT_FOUND));

        SubstitutionRequest request = substitutionRequestRepository.findByTeamAndId(team, requestId)
                .orElseThrow(() -> new SubstitutionException(ErrorStatus.SUBSTITUTION_NOT_FOUND));

        if (!request.getRequester().getId().equals(requesterMember.getId())) {
            throw new SubstitutionException(ErrorStatus.NOT_SUBSTITUTION_REQUESTER);
        }
        return request;
    }

    // 헬퍼 메서드: 대타 요청 조회 및 대타자가 맞는지 검증
    private SubstitutionRequest findSubstitutionAndValidateSubstitute(Long teamId, Long requestId, Member substituteMember) {
        Team team = teamRepository.findById(teamId)
                .orElseThrow(() -> new GeneralException(ErrorStatus.TEAM_NOT_FOUND));

        SubstitutionRequest request = substitutionRequestRepository.findByTeamAndId(team, requestId)
                .orElseThrow(() -> new SubstitutionException(ErrorStatus.SUBSTITUTION_NOT_FOUND));

        if (request.getSubstitute() == null || !request.getSubstitute().getId().equals(substituteMember.getId())) {
            throw new SubstitutionException(ErrorStatus.NOT_SUBSTITUTION_SUBSTITUTE);
        }
        return request;
    }
}