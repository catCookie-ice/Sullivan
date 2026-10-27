package sullivan.serverjava.service;

import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import sullivan.serverjava.common.ApiException;
import sullivan.serverjava.common.IdGenerator;
import sullivan.serverjava.domain.AbuseReport;
import sullivan.serverjava.domain.AbuseRestriction;
import sullivan.serverjava.domain.ActivityGroup;
import sullivan.serverjava.domain.Appeal;
import sullivan.serverjava.domain.Organization;
import sullivan.serverjava.domain.Penalty;
import sullivan.serverjava.domain.PenaltyPart;
import sullivan.serverjava.domain.PresetPenalty;
import sullivan.serverjava.domain.PresetPenaltyPart;
import sullivan.serverjava.domain.UserAccount;
import sullivan.serverjava.domain.VolunteerActivity;
import sullivan.serverjava.mapper.AbuseReportMapper;
import sullivan.serverjava.mapper.AbuseRestrictionMapper;
import sullivan.serverjava.mapper.ActivityGroupMapper;
import sullivan.serverjava.mapper.AppealMapper;
import sullivan.serverjava.mapper.OrganizationMapper;
import sullivan.serverjava.mapper.PenaltyMapper;
import sullivan.serverjava.mapper.PenaltyPartMapper;
import sullivan.serverjava.mapper.PresetPenaltyMapper;
import sullivan.serverjava.mapper.PresetPenaltyPartMapper;
import sullivan.serverjava.mapper.UserAccountMapper;
import sullivan.serverjava.mapper.VolunteerActivityMapper;
import sullivan.serverjava.web.AbuseDtos.AdminReportResponse;
import sullivan.serverjava.web.AbuseDtos.AppealResponse;
import sullivan.serverjava.web.AbuseDtos.MergedReportGroup;
import sullivan.serverjava.web.AbuseDtos.PenaltyResponse;
import sullivan.serverjava.web.AbuseDtos.PresetPartRequest;
import sullivan.serverjava.web.AbuseDtos.PresetPartResponse;
import sullivan.serverjava.web.AbuseDtos.PresetResponse;
import sullivan.serverjava.web.AbuseDtos.ReceivedReportResponse;
import sullivan.serverjava.web.AbuseDtos.ReportResponse;
import sullivan.serverjava.web.AbuseDtos.TargetHistoryResponse;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 举报治理：统一举报（活动/机构/群聊/个人）+ 预设处罚 + 申诉 + 恶意举报阈值升级。
 *
 * <p><b>处罚不许随手开</b>：管理员处置只能「驳回」或从 {@code ACTIVE} 预设中选择一项；
 * 新预设由管理员申请、<b>另一名</b>管理员批准（自批拒绝），防止一人既造刑罚又用刑罚。</p>
 *
 * <p><b>恶意举报的对称治理</b>：举报被驳回后，被举报人可<b>匿名反诉</b>（系统以被举报人
 * 名义生成 REPORT_ABUSE 举报，目标=原举报人；被举报方全程看不到对方身份）。每次反诉
 * 与驳回都会触发对举报人的评估：近 N 天举报中驳回占大多数且数量到线 → 锁举报功能；
 * 更多 → 禁止参加活动并踢出未结束活动；再多 → 封禁账户。</p>
 */
@Service
public class AbuseService {

    public static final String CATEGORY_ACTIVITY = "ACTIVITY";
    public static final String CATEGORY_ORGANIZATION = "ORGANIZATION";
    public static final String CATEGORY_GROUP = "GROUP";
    public static final String CATEGORY_USER = "USER";
    public static final String CATEGORY_REPORT_ABUSE = "REPORT_ABUSE";

    private static final int MAX_PAGE_SIZE = 100;

    private final AbuseReportMapper reportMapper;
    private final PresetPenaltyMapper presetMapper;
    private final PresetPenaltyPartMapper presetPartMapper;
    private final PenaltyMapper penaltyMapper;
    private final PenaltyPartMapper penaltyPartMapper;
    private final AppealMapper appealMapper;
    private final AbuseRestrictionMapper restrictionMapper;
    private final ActivityGroupMapper groupMapper;
    private final OrganizationMapper organizationMapper;
    private final UserAccountMapper userAccountMapper;
    private final VolunteerActivityMapper activityMapper;
    private final ActivityGroupService groupService;
    private final ActivityEnrollmentService enrollmentService;
    private final NotificationService notificationService;
    private final AbuseGuardService abuseGuardService;

    @Value("${abuse.window-days:30}")
    private int windowDays;
    @Value("${abuse.lock-threshold:10}")
    private int lockThreshold;
    @Value("${abuse.activity-ban-threshold:15}")
    private int activityBanThreshold;
    @Value("${abuse.account-ban-threshold:20}")
    private int accountBanThreshold;
    @Value("${abuse.dismiss-ratio:0.6}")
    private double dismissRatio;

    public AbuseService(AbuseReportMapper reportMapper, PresetPenaltyMapper presetMapper,
                        PresetPenaltyPartMapper presetPartMapper, PenaltyMapper penaltyMapper,
                        PenaltyPartMapper penaltyPartMapper, AppealMapper appealMapper,
                        AbuseRestrictionMapper restrictionMapper, ActivityGroupMapper groupMapper,
                        OrganizationMapper organizationMapper, UserAccountMapper userAccountMapper,
                        VolunteerActivityMapper activityMapper, ActivityGroupService groupService,
                        ActivityEnrollmentService enrollmentService, NotificationService notificationService,
                        AbuseGuardService abuseGuardService) {
        this.reportMapper = reportMapper;
        this.presetMapper = presetMapper;
        this.presetPartMapper = presetPartMapper;
        this.penaltyMapper = penaltyMapper;
        this.penaltyPartMapper = penaltyPartMapper;
        this.appealMapper = appealMapper;
        this.restrictionMapper = restrictionMapper;
        this.groupMapper = groupMapper;
        this.organizationMapper = organizationMapper;
        this.userAccountMapper = userAccountMapper;
        this.activityMapper = activityMapper;
        this.groupService = groupService;
        this.enrollmentService = enrollmentService;
        this.notificationService = notificationService;
        this.abuseGuardService = abuseGuardService;
    }

    // ---------- 发起举报（志愿者） ----------

    @Transactional
    public ReportResponse createReport(String reporterUid, String category, String targetId, String reason) {
        abuseGuardService.assertCanReport(reporterUid);
        if (!List.of(CATEGORY_ACTIVITY, CATEGORY_ORGANIZATION, CATEGORY_GROUP, CATEGORY_USER).contains(category)) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "不支持的举报类别");
        }
        if (CATEGORY_USER.equals(category) && reporterUid.equals(targetId)) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "不能举报自己");
        }
        requireTargetExists(category, targetId);
        Long duplicated = reportMapper.selectCount(Wrappers.<AbuseReport>lambdaQuery()
                .eq(AbuseReport::getReporterUid, reporterUid)
                .eq(AbuseReport::getCategory, category)
                .eq(AbuseReport::getStatus, "PENDING")
                .eq(targetColumn(category), targetId));
        if (duplicated != null && duplicated > 0) {
            throw new ApiException(HttpStatus.CONFLICT, "你已举报过该对象，请等待处理结果");
        }
        AbuseReport report = new AbuseReport();
        report.setId(IdGenerator.next());
        report.setStatus("PENDING");
        report.setCategory(category);
        report.setReporterUid(reporterUid);
        report.setReason(reason.strip());
        applyTarget(report, category, targetId);
        reportMapper.insert(report);
        return toReportResponse(report);
    }

    /**
     * 反向举报：举报被驳回后，被举报人可匿名反诉恶意举报。
     * 系统以被举报人名义生成 REPORT_ABUSE 举报，目标=原举报人；随后立即评估原举报人。
     */
    @Transactional
    public ReportResponse counterReport(String uid, String sourceReportId, String reason) {
        AbuseReport source = requireReport(sourceReportId);
        if (!"DISMISSED".equals(source.getStatus())) {
            throw new ApiException(HttpStatus.CONFLICT, "只有被驳回的举报才能反诉");
        }
        boolean iAmUserTarget = uid.equals(source.getTargetUserId());
        boolean iAmOrgTarget = uid.equals(source.getTargetOrgId());
        if (!iAmUserTarget && !iAmOrgTarget) {
            throw new ApiException(HttpStatus.FORBIDDEN, "只有被举报人可以反诉");
        }
        if (!iAmUserTarget) {
            throw new ApiException(HttpStatus.FORBIDDEN, "反诉由被举报的个人发起");
        }
        Long existed = reportMapper.selectCount(Wrappers.<AbuseReport>lambdaQuery()
                .eq(AbuseReport::getSourceReportId, sourceReportId));
        if (existed != null && existed > 0) {
            throw new ApiException(HttpStatus.CONFLICT, "该举报已被反诉过");
        }
        AbuseReport report = new AbuseReport();
        report.setId(IdGenerator.next());
        report.setStatus("PENDING");
        report.setCategory(CATEGORY_REPORT_ABUSE);
        report.setReporterUid(uid);
        report.setReason(reason.strip());
        report.setTargetUserId(source.getReporterUid());
        report.setSourceReportId(sourceReportId);
        reportMapper.insert(report);
        // 反诉即评估：原举报人近期大量举报且驳回占大多数 → 直接按阈值升级限制。
        evaluateReporter(source.getReporterUid());
        return toReportResponse(report);
    }

    /** 我的举报（含反诉）。 */
    public List<ReportResponse> myReports(String uid) {
        return reportMapper.selectList(Wrappers.<AbuseReport>lambdaQuery()
                .eq(AbuseReport::getReporterUid, uid)
                .orderByDesc(AbuseReport::getCreatedAt))
                .stream().map(this::toReportResponse).toList();
    }

    /**
     * 针对我的举报：只返回被驳回的（这是反诉入口，不是知情清单）——
     * 待处理的与已处罚的不下发，处置意见也不下发；举报人匿名。
     */
    public List<ReceivedReportResponse> receivedReports(String uid) {
        List<AbuseReport> rows = reportMapper.selectList(Wrappers.<AbuseReport>lambdaQuery()
                .eq(AbuseReport::getTargetUserId, uid)
                .eq(AbuseReport::getStatus, "DISMISSED")
                .ne(AbuseReport::getCategory, CATEGORY_REPORT_ABUSE)
                .orderByDesc(AbuseReport::getCreatedAt));
        return rows.stream().map(report -> {
            boolean countered = reportMapper.selectCount(Wrappers.<AbuseReport>lambdaQuery()
                    .eq(AbuseReport::getSourceReportId, report.getId())) > 0;
            return new ReceivedReportResponse(report.getId(), report.getCategory(), report.getReason(),
                    report.getHandledAt(), !countered, report.getCreatedAt());
        }).toList();
    }

    // ---------- 管理员：列表 / 档案 / 处置 ----------

    public IPage<AdminReportResponse> adminList(String status, String category, long page, long size) {
        Page<AbuseReport> p = reportMapper.selectPage(new Page<>(page, Math.min(size, MAX_PAGE_SIZE)),
                Wrappers.<AbuseReport>lambdaQuery()
                        .eq(status != null && !status.isBlank(), AbuseReport::getStatus, status)
                        .eq(category != null && !category.isBlank(), AbuseReport::getCategory, category)
                        .orderByDesc(AbuseReport::getCreatedAt));
        Map<String, String> names = loginNamesOf(p.getRecords().stream()
                .map(AbuseReport::getReporterUid).collect(Collectors.toSet()));
        return p.convert(report -> toAdminResponse(report, names));
    }

    /**
     * 管理员举报列表（合并视图）：对同一对象的举报合并为一条（含计数与最新状态），
     * 展开后可见每条举报；排序按对象最新举报时间倒序。
     */
    public List<MergedReportGroup> adminListGrouped(String status, String category) {
        List<AbuseReport> rows = reportMapper.selectList(Wrappers.<AbuseReport>lambdaQuery()
                .eq(status != null && !status.isBlank(), AbuseReport::getStatus, status)
                .eq(category != null && !category.isBlank(), AbuseReport::getCategory, category)
                .orderByDesc(AbuseReport::getCreatedAt));
        Map<String, String> names = loginNamesOf(rows.stream()
                .map(AbuseReport::getReporterUid).collect(Collectors.toSet()));
        Map<String, List<AbuseReport>> groups = new LinkedHashMap<>();
        for (AbuseReport report : rows) {
            groups.computeIfAbsent(groupKey(report), key -> new ArrayList<>()).add(report);
        }
        return groups.values().stream().map(reports -> {
            Map<String, String> groupNames = loginNamesOf(reports.stream()
                    .map(AbuseReport::getReporterUid).collect(Collectors.toSet()));
            List<AdminReportResponse> items = reports.stream()
                    .map(report -> toAdminResponse(report, groupNames)).toList();
            AbuseReport latest = reports.get(0);
            return new MergedReportGroup(latest.getCategory(),
                    targetSummary(latest),
                    latest.getTargetActivityId(), latest.getTargetOrgId(),
                    latest.getTargetGroupId(), latest.getTargetUserId(),
                    reports.size(),
                    reports.stream().filter(r -> "PENDING".equals(r.getStatus())).count(),
                    latest.getStatus(), latest.getCreatedAt(), items);
        }).toList();
    }

    /** 同一对象的分组键：类别 + 目标 id（反诉举报按其目标用户归组）。 */
    private String groupKey(AbuseReport report) {
        return report.getCategory() + "|" + report.getTargetActivityId() + "|" + report.getTargetOrgId()
                + "|" + report.getTargetGroupId() + "|" + report.getTargetUserId();
    }

    /** 目标档案：历史被举报 + 违规（处罚）记录。管理员可见举报人身份。 */
    public TargetHistoryResponse targetHistory(String category, String targetId) {
        List<AbuseReport> reports = reportMapper.selectList(Wrappers.<AbuseReport>lambdaQuery()
                .eq(targetColumn(category), targetId)
                .orderByDesc(AbuseReport::getCreatedAt));
        Map<String, String> names = loginNamesOf(reports.stream()
                .map(AbuseReport::getReporterUid).collect(Collectors.toSet()));
        List<AdminReportResponse> reportResponses = reports.stream()
                .map(report -> toAdminResponse(report, names)).toList();
        List<PenaltyResponse> penalties = penaltyMapper.selectList(Wrappers.<Penalty>lambdaQuery()
                .eq(penaltyTargetColumn(category), targetId)
                .orderByDesc(Penalty::getAppliedAt))
                .stream().map(this::toPenaltyResponse).toList();
        return new TargetHistoryResponse(reportResponses, penalties);
    }

    /** 处置：驳回（触发对举报人的阈值评估）或从 ACTIVE 预设中施加处罚。 */
    @Transactional
    public void decide(String adminId, String reportId, boolean dismiss, String presetId, String note) {
        AbuseReport report = requireReport(reportId);
        if (!"PENDING".equals(report.getStatus())) {
            throw new ApiException(HttpStatus.CONFLICT, "该举报已处理");
        }
        if (dismiss) {
            report.setStatus("DISMISSED");
            report.setHandledBy(adminId);
            report.setHandledAt(LocalDateTime.now());
            report.setDecisionNote(note == null ? null : note.strip());
            reportMapper.updateById(report);
            notificationService.notify(report.getReporterUid(), "USER",
                    NotificationService.TYPE_REPORT_DISMISSED,
                    "你的举报已被驳回",
                    "你举报的" + categoryLabel(report.getCategory()) + "经核查未发现违规",
                    "/volunteer/enrollments");
            // 驳回是阈值评估的触发点之一：被驳回多了，举报人自己会升级受限。
            evaluateReporter(report.getReporterUid());
            return;
        }
        PresetPenalty preset = presetId == null || presetId.isBlank()
                ? null : presetMapper.selectById(presetId);
        if (preset == null || !"ACTIVE".equals(preset.getStatus())) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "请选择一项生效中的预设处罚");
        }
        if (!report.getCategory().equals(preset.getCategory())) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "预设处罚与举报对象类型不匹配");
        }
        Penalty penalty = applyPenalty(preset, report, adminId, note);
        report.setStatus("RESOLVED");
        report.setHandledBy(adminId);
        report.setHandledAt(LocalDateTime.now());
        report.setDecisionNote(note == null ? null : note.strip());
        report.setPenaltyId(penalty.getId());
        reportMapper.updateById(report);
    }

    // ---------- 预设处罚（组合式：主基础惩罚 + 跨对象附带组件） ----------

    /** 每种对象自己的基础惩罚集（顺序即展示顺序）。 */
    private static final Map<String, List<String>> BASE_ACTIONS = Map.of(
            CATEGORY_ACTIVITY, List.of("TEMP_DELIST", "DELIST"),
            CATEGORY_ORGANIZATION, List.of("BAN_ORG", "BAN_PUBLISH"),
            CATEGORY_GROUP, List.of("PERM_CLOSE", "BAN_GROUP"),
            CATEGORY_USER, List.of("BAN_USER"));
    /** 必须填时长的暂时类基础惩罚。 */
    private static final Set<String> NEEDS_DAYS = Set.of("TEMP_DELIST", "BAN_GROUP");
    /** 允许带时长的（其余为纯永久动作）。 */
    private static final Set<String> ALLOWS_DAYS = Set.of("TEMP_DELIST", "BAN_GROUP", "BAN_ORG", "BAN_PUBLISH", "BAN_USER");
    private static final Set<String> PART_KINDS = Set.of(CATEGORY_ACTIVITY, CATEGORY_ORGANIZATION, CATEGORY_GROUP);

    /**
     * 申请新增预设：进 PENDING，等另一名管理员批准。
     * 预设 = 主基础惩罚（作用于举报对象）+ 若干跨对象附带组件（复合共存；
     * 附带时长留空 = 跟随主处罚时长，主处罚永久则同为永久）。
     */
    @Transactional
    public PresetResponse createPreset(String adminId, String name, String category, String action,
                                       Integer durationDays, List<PresetPartRequest> parts, String description) {
        validatePresetShape(category, action, durationDays, parts);
        PresetPenalty preset = new PresetPenalty();
        preset.setId(IdGenerator.next());
        preset.setStatus("PENDING");
        preset.setCategory(category);
        preset.setAction(action);
        preset.setDurationDays(durationDays);
        preset.setName(name.strip());
        preset.setDescription(description == null ? null : description.strip());
        preset.setCreatedBy(adminId);
        presetMapper.insert(preset);
        for (PresetPartRequest part : parts == null ? List.<PresetPartRequest>of() : parts) {
            PresetPenaltyPart entity = new PresetPenaltyPart();
            entity.setId(IdGenerator.next());
            entity.setPresetId(preset.getId());
            entity.setTargetKind(part.targetKind());
            entity.setAction(part.action());
            entity.setDurationDays(part.durationDays());
            presetPartMapper.insert(entity);
        }
        return toPresetResponse(preset, parts);
    }

    public List<PresetResponse> listPresets(String status) {
        List<PresetResponse> presets = presetMapper.selectList(Wrappers.<PresetPenalty>lambdaQuery()
                        .eq(status != null && !status.isBlank(), PresetPenalty::getStatus, status)
                        .orderByDesc(PresetPenalty::getCreatedAt))
                .stream().map(preset -> toPresetResponse(preset, null)).toList();
        // 批量补齐附带组件（按行内一次查询，避免 N+1）。
        if (!presets.isEmpty()) {
            Map<String, List<PresetPenaltyPart>> partsByPreset = presetPartMapper.selectList(
                            Wrappers.<PresetPenaltyPart>lambdaQuery().orderByAsc(PresetPenaltyPart::getCreatedAt))
                    .stream().collect(Collectors.groupingBy(PresetPenaltyPart::getPresetId));
            return presets.stream()
                    .map(preset -> new PresetResponse(preset.id(), preset.name(), preset.category(), preset.action(),
                            preset.durationDays(),
                            partsByPreset.getOrDefault(preset.id(), List.of()).stream()
                                    .map(part -> new PresetPartResponse(part.getTargetKind(), part.getAction(),
                                            part.getDurationDays())).toList(),
                            preset.description(), preset.status(), preset.createdBy(), preset.approvedBy(),
                            preset.handledAt(), preset.createdAt()))
                    .toList();
        }
        return presets;
    }

    private List<PresetPartResponse> partResponses(String presetId) {
        return presetPartMapper.selectList(Wrappers.<PresetPenaltyPart>lambdaQuery()
                        .eq(PresetPenaltyPart::getPresetId, presetId)
                        .orderByAsc(PresetPenaltyPart::getCreatedAt))
                .stream().map(part -> new PresetPartResponse(part.getTargetKind(), part.getAction(),
                        part.getDurationDays())).toList();
    }

    private PresetResponse toPresetResponse(PresetPenalty preset, List<PresetPartRequest> parts) {
        List<PresetPartResponse> partResponses = parts == null
                ? partResponses(preset.getId())
                : parts.stream().map(part -> new PresetPartResponse(part.targetKind(), part.action(),
                        part.durationDays())).toList();
        return new PresetResponse(preset.getId(), preset.getName(), preset.getCategory(), preset.getAction(),
                preset.getDurationDays(), partResponses, preset.getDescription(), preset.getStatus(),
                preset.getCreatedBy(), preset.getApprovedBy(), preset.getHandledAt(), preset.getCreatedAt());
    }

    /**
     * 组合校验：主惩罚须是预设对象的基础惩罚之一；附带组件须跨对象（不与预设对象相同、
     * 不重复）、取目标对象的基础惩罚集；暂时类必须可解析出时长（自带，或主处罚时长非空）。
     */
    private void validatePresetShape(String category, String action, Integer durationDays,
                                     List<PresetPartRequest> parts) {
        if (!BASE_ACTIONS.containsKey(category)) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "预设处罚对象类型不支持");
        }
        validateSingle(category, action, durationDays);
        if (CATEGORY_USER.equals(category) && parts != null && !parts.isEmpty()) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "个人处罚没有可附带的对象");
        }
        Set<String> seen = new java.util.HashSet<>();
        for (PresetPartRequest part : parts == null ? List.<PresetPartRequest>of() : parts) {
            String kind = part.targetKind();
            if (kind == null || !PART_KINDS.contains(kind)) {
                throw new ApiException(HttpStatus.BAD_REQUEST, "附带对象类型不支持");
            }
            if (kind.equals(category)) {
                throw new ApiException(HttpStatus.BAD_REQUEST, "附带对象不能与处罚对象相同");
            }
            if (!seen.add(kind)) {
                throw new ApiException(HttpStatus.BAD_REQUEST, "同一对象的附带惩罚只需一条");
            }
            validateSingle(kind, part.action(), part.durationDays());
            // 暂时类附带在主处罚为永久（主时长空）时必须自带时长。
            if (NEEDS_DAYS.contains(part.action()) && part.durationDays() == null && durationDays == null) {
                throw new ApiException(HttpStatus.BAD_REQUEST, "附带暂时处罚必须填写时长（主处罚为永久）");
            }
            // 纯永久附带动作不允许带时长。
            if (!ALLOWS_DAYS.contains(part.action()) && part.durationDays() != null) {
                throw new ApiException(HttpStatus.BAD_REQUEST, "附带动作是纯永久动作，不能填写时长");
            }
        }
    }

    /** 单个基础惩罚与时长校验（主/附带共用）。 */
    private void validateSingle(String kind, String action, Integer durationDays) {
        List<String> allowed = BASE_ACTIONS.get(kind);
        if (action == null || !allowed.contains(action)) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "处罚动作不是该对象的基础惩罚");
        }
        if (durationDays != null && durationDays < 0) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "处罚天数不能为负");
        }
        if (NEEDS_DAYS.contains(action) && (durationDays == null || durationDays < 1)) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "该基础处罚必须填写时长");
        }
        if (!ALLOWS_DAYS.contains(action) && durationDays != null) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "该基础处罚是纯永久动作，不能填写时长");
        }
    }

    /** 申诉列表（管理员）。 */
    public List<AppealResponse> adminListAppeals(String status) {
        return appealMapper.selectList(Wrappers.<Appeal>lambdaQuery()
                        .eq(status != null && !status.isBlank(), Appeal::getStatus, status)
                        .orderByDesc(Appeal::getCreatedAt))
                .stream().map(this::toAppealResponse).toList();
    }

    /** 审批预设：批准 → ACTIVE；驳回 → REJECTED。不能审批自己申请的。 */
    @Transactional
    public void approvePreset(String adminId, String presetId, boolean approve) {
        PresetPenalty preset = requirePreset(presetId);
        if (!"PENDING".equals(preset.getStatus())) {
            throw new ApiException(HttpStatus.CONFLICT, "该预设已审批");
        }
        if (adminId.equals(preset.getCreatedBy())) {
            throw new ApiException(HttpStatus.FORBIDDEN, "不能审批自己申请的预设处罚");
        }
        preset.setStatus(approve ? "ACTIVE" : "REJECTED");
        preset.setApprovedBy(adminId);
        preset.setHandledAt(LocalDateTime.now());
        presetMapper.updateById(preset);
    }

    // ---------- 申诉 ----------

    @Transactional
    public AppealResponse createAppeal(String appellantUid, String appellantType, String penaltyId, String reason) {
        Penalty penalty = requirePenalty(penaltyId);
        boolean mine = "USER".equals(appellantType)
                ? appellantUid.equals(penalty.getTargetUserId())
                : appellantUid.equals(penalty.getTargetOrgId());
        if (!mine) {
            throw new ApiException(HttpStatus.FORBIDDEN, "只能申诉针对自己的处罚");
        }
        if (!"ACTIVE".equals(penalty.getStatus())) {
            throw new ApiException(HttpStatus.CONFLICT, "该处罚不在申诉期");
        }
        Long existed = appealMapper.selectCount(Wrappers.<Appeal>lambdaQuery()
                .eq(Appeal::getPenaltyId, penaltyId));
        if (existed != null && existed > 0) {
            throw new ApiException(HttpStatus.CONFLICT, "该处罚已申诉过，请等待处理结果");
        }
        Appeal appeal = new Appeal();
        appeal.setId(IdGenerator.next());
        appeal.setStatus("PENDING");
        appeal.setPenaltyId(penaltyId);
        appeal.setAppellantType(appellantType);
        appeal.setAppellantUid(appellantUid);
        appeal.setReason(reason.strip());
        appealMapper.insert(appeal);
        return toAppealResponse(appeal);
    }

    /** 处理申诉：维持（UPHELD）或撤销（OVERTURNED，联动解除限制/恢复账户/解除封禁）。 */
    @Transactional
    public void handleAppeal(String adminId, String appealId, boolean uphold, String note) {
        Appeal appeal = appealMapper.selectById(appealId);
        if (appeal == null) {
            throw new ApiException(HttpStatus.NOT_FOUND, "申诉不存在");
        }
        if (!"PENDING".equals(appeal.getStatus())) {
            throw new ApiException(HttpStatus.CONFLICT, "该申诉已处理");
        }
        appeal.setStatus(uphold ? "UPHELD" : "OVERTURNED");
        appeal.setHandledBy(adminId);
        appeal.setHandledAt(LocalDateTime.now());
        appeal.setDecisionNote(note == null ? null : note.strip());
        appealMapper.updateById(appeal);
        Penalty penalty = requirePenalty(appeal.getPenaltyId());
        if (!uphold) {
            liftPenalty(penalty, adminId, note);
        }
        String recipientType = "USER".equals(appeal.getAppellantType()) ? "USER" : "ORGANIZATION";
        String link = "USER".equals(appeal.getAppellantType()) ? "/volunteer/penalties" : "/penalties";
        notificationService.notify(appeal.getAppellantUid(), recipientType, NotificationService.TYPE_APPEAL_DECIDED,
                uphold ? "申诉结果：维持处罚" : "申诉结果：处罚已撤销",
                "你的申诉已处理：" + (uphold ? "维持原处罚" : "处罚已撤销，相关限制同步解除"),
                link);
    }

    /** 撤销处罚：处罚置 LIFTED + 其产生的限制全部解除 + 恢复被连带禁用的账户。 */
    private void liftPenalty(Penalty penalty, String adminId, String note) {
        penalty.setStatus("LIFTED");
        penalty.setLiftedBy(adminId);
        penalty.setLiftedAt(LocalDateTime.now());
        penalty.setLiftNote(note == null ? null : note.strip());
        penaltyMapper.updateById(penalty);
        restrictionMapper.update(null, Wrappers.<AbuseRestriction>lambdaUpdate()
                .set(AbuseRestriction::getStatus, "LIFTED")
                .setSql("version = version + 1")
                .eq(AbuseRestriction::getPenaltyId, penalty.getId())
                .eq(AbuseRestriction::getStatus, "ACTIVE"));
        if (Boolean.TRUE.equals(penalty.getAccountDisabled()) && penalty.getTargetUserId() != null) {
            userAccountMapper.update(null, Wrappers.<UserAccount>lambdaUpdate()
                    .set(UserAccount::getStatus, "ENABLED")
                    .setSql("version = version + 1")
                    .eq(UserAccount::getId, penalty.getTargetUserId())
                    .eq(UserAccount::getStatus, "DISABLED"));
        }
    }

    // ---------- 我的处罚 ----------

    public List<PenaltyResponse> penaltiesFor(String recipientType, String uid) {
        List<Penalty> penalties = "USER".equals(recipientType)
                ? penaltyMapper.selectList(Wrappers.<Penalty>lambdaQuery()
                        .eq(Penalty::getTargetUserId, uid).orderByDesc(Penalty::getAppliedAt))
                : penaltyMapper.selectList(Wrappers.<Penalty>lambdaQuery()
                        .eq(Penalty::getTargetOrgId, uid).orderByDesc(Penalty::getAppliedAt));
        return penalties.stream().map(this::toPenaltyResponse).toList();
    }

    // ---------- 处罚执行（组合式：主组件 + 跨对象附带组件） ----------

    /** 组件执行所需的目标 id（按举报对象联动解析：群→活动/机构、活动→机构）。 */
    private record PartTargets(String activityId, String orgId, String groupId) {
    }

    private PartTargets resolveTargets(AbuseReport report) {
        String groupId = report.getTargetGroupId();
        String activityId = report.getTargetActivityId();
        String orgId = report.getTargetOrgId();
        if (activityId == null && groupId != null) {
            activityId = activityIdOfGroup(groupId);
        }
        if (orgId == null && groupId != null) {
            orgId = orgIdOfGroup(groupId);
        }
        if (orgId == null && activityId != null) {
            orgId = orgIdOfActivity(activityId, null);
        }
        return new PartTargets(activityId, orgId, groupId);
    }

    private Penalty applyPenalty(PresetPenalty preset, AbuseReport report, String adminId, String note) {
        LocalDateTime now = LocalDateTime.now();
        Penalty penalty = new Penalty();
        penalty.setId(IdGenerator.next());
        penalty.setStatus("ACTIVE");
        penalty.setPresetId(preset.getId());
        penalty.setReportId(report.getId());
        penalty.setCategory(preset.getCategory());
        penalty.setAction(preset.getAction());
        penalty.setDurationDays(preset.getDurationDays());
        penalty.setTargetActivityId(report.getTargetActivityId());
        penalty.setTargetOrgId(report.getTargetOrgId());
        penalty.setTargetGroupId(report.getTargetGroupId());
        penalty.setTargetUserId(report.getTargetUserId());
        penalty.setReason((report.getReason() + (note == null || note.isBlank() ? "" : "；" + note.strip())).strip());
        penalty.setAppliedBy(adminId);
        penalty.setAppliedAt(now);
        penalty.setAccountDisabled(false);
        penalty.setExpiresAt(preset.getDurationDays() == null ? null : now.plusDays(preset.getDurationDays()));
        penaltyMapper.insert(penalty); // 先落处罚行：组件行有外键引用

        // 主组件：作用于举报对象本身（个人处罚只有主组件，不入组件表）。
        if (CATEGORY_USER.equals(preset.getCategory())) {
            banUser(report.getTargetUserId(), preset.getDurationDays(), penalty.getId(),
                    adminId, "举报核实后封禁账户");
        } else {
            PartTargets targets = resolveTargets(report);
            executePart(penalty.getId(), preset.getCategory(), preset.getAction(), targets,
                    preset.getDurationDays(), adminId, "举报核实后");
            recordPart(penalty.getId(), preset.getCategory(), preset.getAction(), targets,
                    preset.getDurationDays(), now);
        }
        // 附带组件：跨对象复合共存；附带时长留空 = 跟随主处罚时长（主处罚永久则同为永久）。
        for (PresetPenaltyPart part : presetPartMapper.selectList(Wrappers.<PresetPenaltyPart>lambdaQuery()
                .eq(PresetPenaltyPart::getPresetId, preset.getId())
                .orderByAsc(PresetPenaltyPart::getCreatedAt))) {
            Integer days = part.getDurationDays() != null ? part.getDurationDays() : preset.getDurationDays();
            PartTargets targets = resolveTargets(report);
            executePart(penalty.getId(), part.getTargetKind(), part.getAction(), targets,
                    days, adminId, "举报核实后附带");
            recordPart(penalty.getId(), part.getTargetKind(), part.getAction(), targets, days, now);
        }
        // 通知被处罚方（附申诉指引）。
        if (penalty.getTargetUserId() != null) {
            notificationService.notify(penalty.getTargetUserId(), "USER",
                    NotificationService.TYPE_PENALTY_APPLIED,
                    "你已被处罚：" + preset.getName(),
                    penalty.getReason() + "。如对处罚有异议，可在「我的处罚」中申诉。",
                    "/volunteer/penalties");
        } else if (penalty.getTargetGroupId() != null) {
            // 群聊处罚：通知管理该群的机构。
            ActivityGroup group = groupMapper.selectById(penalty.getTargetGroupId());
            if (group != null) {
                notificationService.notify(group.getOrganizationId(), "ORGANIZATION",
                        NotificationService.TYPE_PENALTY_APPLIED,
                        "群聊已被处罚：" + preset.getName(),
                        penalty.getReason() + "。如对处罚有异议，可在「处罚与申诉」中申诉。",
                        "/penalties");
            }
        } else if (penalty.getTargetOrgId() != null) {
            notificationService.notify(penalty.getTargetOrgId(), "ORGANIZATION",
                    NotificationService.TYPE_PENALTY_APPLIED,
                    "机构已被处罚：" + preset.getName(),
                    penalty.getReason() + "。如对处罚有异议，可在「我的处罚」中申诉。",
                    "/penalties");
        }
        return penalty;
    }

    /**
     * 执行一个基础惩罚组件（主/附带共用）。
     * 目标 id 解析不到则跳过（如机构处罚附带「下架活动」但没有具体活动——机构面联动已覆盖）。
     */
    private void executePart(String penaltyId, String kind, String action, PartTargets targets,
                             Integer days, String adminId, String reasonPrefix) {
        LocalDateTime until = days == null ? null : LocalDateTime.now().plusDays(days);
        switch (kind) {
            case CATEGORY_ACTIVITY -> {
                if (targets.activityId() == null) {
                    return;
                }
                if ("DELIST".equals(action)) {
                    delistActivity(targets.activityId(), reasonPrefix + "下架");
                } else {
                    suspendActivity(targets.activityId(), until); // TEMP_DELIST，其群聊同时长暂时封禁
                }
            }
            case CATEGORY_ORGANIZATION -> {
                if (targets.orgId() == null) {
                    return;
                }
                if ("BAN_PUBLISH".equals(action)) {
                    banPublish(targets.orgId(), days, penaltyId, adminId, reasonPrefix + "禁止发布活动");
                } else {
                    banOrg(targets.orgId(), days, penaltyId, adminId, reasonPrefix + "封禁机构");
                    applyOrgActivityFallout(targets.orgId(), days);
                }
            }
            case CATEGORY_GROUP -> {
                if (targets.groupId() == null) {
                    return;
                }
                if ("PERM_CLOSE".equals(action)) {
                    groupService.closeGroupById(targets.groupId(), reasonPrefix + "永久封禁群聊");
                } else {
                    groupService.suspendGroupById(targets.groupId(), until); // BAN_GROUP
                }
            }
            default -> throw new ApiException(HttpStatus.INTERNAL_SERVER_ERROR, "未知处罚对象类型");
        }
    }

    /** 组件执行留痕：解析后的目标与到期时刻落 penalty_part（目标无法解析则不落）。 */
    private void recordPart(String penaltyId, String kind, String action, PartTargets targets,
                            Integer days, LocalDateTime now) {
        String activityId = CATEGORY_ACTIVITY.equals(kind) ? targets.activityId() : null;
        String orgId = CATEGORY_ORGANIZATION.equals(kind) ? targets.orgId() : null;
        String groupId = CATEGORY_GROUP.equals(kind) ? targets.groupId() : null;
        if (activityId == null && orgId == null && groupId == null) {
            return;
        }
        PenaltyPart row = new PenaltyPart();
        row.setId(IdGenerator.next());
        row.setPenaltyId(penaltyId);
        row.setTargetKind(kind);
        row.setAction(action);
        row.setTargetActivityId(activityId);
        row.setTargetOrgId(orgId);
        row.setTargetGroupId(groupId);
        row.setDurationDays(days);
        row.setExpiresAt(days == null ? null : now.plusDays(days));
        penaltyPartMapper.insert(row);
    }

    /** 下架活动：未结束的（RECRUITING/IN_PROGRESS）转 CANCELLED，并封闭其群聊。 */
    private void delistActivity(String activityId, String reason) {
        if (activityId == null) {
            return;
        }
        activityMapper.update(null, Wrappers.<VolunteerActivity>lambdaUpdate()
                .set(VolunteerActivity::getStatus, "CANCELLED")
                .setSql("version = version + 1")
                .eq(VolunteerActivity::getId, activityId)
                .in(VolunteerActivity::getStatus, "RECRUITING", "IN_PROGRESS"));
        groupService.closeGroupOfActivity(activityId, reason);
    }

    private void delistAllOrgActivities(String orgId, String reason) {
        if (orgId == null) {
            return;
        }
        List<VolunteerActivity> rows = activityMapper.selectList(Wrappers.<VolunteerActivity>lambdaQuery()
                .eq(VolunteerActivity::getOrganizationId, orgId)
                .in(VolunteerActivity::getStatus, "RECRUITING", "IN_PROGRESS"));
        for (VolunteerActivity activity : rows) {
            delistActivity(activity.getId(), reason);
        }
    }

    /** 机构封禁的活动面联动：永久→永久下架并解散群聊；暂时→活动与群聊同时长暂时下架/封禁。 */
    private void applyOrgActivityFallout(String orgId, Integer durationDays) {
        if (orgId == null) {
            return;
        }
        if (durationDays == null) {
            delistAllOrgActivities(orgId, "机构被永久封禁，活动随之下架");
            return;
        }
        suspendAllOrgActivities(orgId, LocalDateTime.now().plusDays(durationDays));
    }

    private String activityIdOfGroup(String groupId) {
        if (groupId == null) {
            return null;
        }
        ActivityGroup group = groupMapper.selectById(groupId);
        return group == null ? null : group.getActivityId();
    }

    private String orgIdOfGroup(String groupId) {
        if (groupId == null) {
            return null;
        }
        ActivityGroup group = groupMapper.selectById(groupId);
        return group == null ? null : group.getOrganizationId();
    }

    /** 暂时下架活动：RECRUITING/IN_PROGRESS → SUSPENDED，其群聊同时长暂时封禁。 */
    private void suspendActivity(String activityId, LocalDateTime until) {
        if (activityId == null || until == null) {
            return;
        }
        activityMapper.update(null, Wrappers.<VolunteerActivity>lambdaUpdate()
                .set(VolunteerActivity::getStatus, "SUSPENDED")
                .setSql("version = version + 1")
                .eq(VolunteerActivity::getId, activityId)
                .in(VolunteerActivity::getStatus, "RECRUITING", "IN_PROGRESS"));
        groupService.suspendGroupOfActivity(activityId, until);
    }

    /** 机构被暂时封禁：其全部未结束活动与群聊「至少暂时下架/封禁」同时长。 */
    private void suspendAllOrgActivities(String orgId, LocalDateTime until) {
        if (orgId == null || until == null) {
            return;
        }
        List<VolunteerActivity> rows = activityMapper.selectList(Wrappers.<VolunteerActivity>lambdaQuery()
                .eq(VolunteerActivity::getOrganizationId, orgId)
                .in(VolunteerActivity::getStatus, "RECRUITING", "IN_PROGRESS"));
        for (VolunteerActivity activity : rows) {
            suspendActivity(activity.getId(), until);
        }
    }

    // ---------- 暂时处罚到期恢复 ----------

    /**
     * 每分钟扫描到期的暂时处罚（TEMP_DELIST / BAN_GROUP / BAN_ORG）：恢复被暂时下架的
     * 活动与群聊，并把处罚行翻成 EXPIRED。恢复用条件更新（eq SUSPENDED），重启可重入；
     * 永久动作无到期时间不进本扫描，封禁类限制仍由 AbuseGuardService 读时判定。
     */
    @Scheduled(cron = "0 * * * * *")
    public void scheduledResumeExpiredPenalties() {
        sweepExpiredPenalties();
    }

    /** 扫描本体（暴露成普通方法便于单测直接调用）。 */
    @Transactional
    public void sweepExpiredPenalties() {
        LocalDateTime now = LocalDateTime.now();
        // 组件路径（V8+）：暂时类组件（暂时下架/暂时封群）到期 → 恢复对应对象；主处罚到期翻 EXPIRED。
        List<PenaltyPart> expiredParts = penaltyPartMapper.selectList(Wrappers.<PenaltyPart>lambdaQuery()
                .in(PenaltyPart::getAction, "TEMP_DELIST", "BAN_GROUP")
                .isNotNull(PenaltyPart::getExpiresAt)
                .le(PenaltyPart::getExpiresAt, now));
        Set<String> composedPenaltyIds = new java.util.HashSet<>();
        Map<String, List<PenaltyPart>> partsByPenalty = expiredParts.stream()
                .collect(Collectors.groupingBy(PenaltyPart::getPenaltyId));
        for (Map.Entry<String, List<PenaltyPart>> entry : partsByPenalty.entrySet()) {
            Penalty penalty = penaltyMapper.selectById(entry.getKey());
            if (penalty == null || !"ACTIVE".equals(penalty.getStatus())) {
                continue;
            }
            composedPenaltyIds.add(penalty.getId());
            for (PenaltyPart part : entry.getValue()) {
                if ("TEMP_DELIST".equals(part.getAction()) && part.getTargetActivityId() != null) {
                    for (VolunteerActivity activity : activitiesOf(part.getTargetActivityId())) {
                        resumeActivity(activity, now);
                    }
                } else if ("BAN_GROUP".equals(part.getAction()) && part.getTargetGroupId() != null) {
                    groupService.resumeGroupById(part.getTargetGroupId());
                }
            }
            penaltyMapper.update(null, Wrappers.<Penalty>lambdaUpdate()
                    .set(Penalty::getStatus, "EXPIRED")
                    .setSql("version = version + 1")
                    .eq(Penalty::getId, penalty.getId())
                    .eq(Penalty::getStatus, "ACTIVE"));
        }
        // 存量路径（V8 前的动作式处罚行）：跳过已组件化的处罚，避免重复恢复。
        List<Penalty> expired = penaltyMapper.selectList(Wrappers.<Penalty>lambdaQuery()
                .eq(Penalty::getStatus, "ACTIVE")
                .in(Penalty::getAction, "TEMP_DELIST", "DELIST_AND_BAN_PUBLISH", "BAN_GROUP", "BAN_ORG")
                .isNotNull(Penalty::getExpiresAt)
                .le(Penalty::getExpiresAt, now));
        for (Penalty penalty : expired) {
            if (composedPenaltyIds.contains(penalty.getId())) {
                continue;
            }
            resumeAfterPenalty(penalty, now);
            penaltyMapper.update(null, Wrappers.<Penalty>lambdaUpdate()
                    .set(Penalty::getStatus, "EXPIRED")
                    .setSql("version = version + 1")
                    .eq(Penalty::getId, penalty.getId())
                    .eq(Penalty::getStatus, "ACTIVE"));
        }
    }

    private void resumeAfterPenalty(Penalty penalty, LocalDateTime now) {
        if ("TEMP_DELIST".equals(penalty.getAction()) || "DELIST_AND_BAN_PUBLISH".equals(penalty.getAction())) {
            for (VolunteerActivity activity : activitiesOf(penalty.getTargetActivityId())) {
                resumeActivity(activity, now);
            }
        } else if ("BAN_ORG".equals(penalty.getAction()) && penalty.getTargetOrgId() != null) {
            List<VolunteerActivity> suspended = activityMapper.selectList(Wrappers.<VolunteerActivity>lambdaQuery()
                    .eq(VolunteerActivity::getOrganizationId, penalty.getTargetOrgId())
                    .eq(VolunteerActivity::getStatus, "SUSPENDED"));
            for (VolunteerActivity activity : suspended) {
                resumeActivity(activity, now);
            }
        } else if ("BAN_GROUP".equals(penalty.getAction()) && penalty.getTargetGroupId() != null) {
            groupService.resumeGroupById(penalty.getTargetGroupId());
        }
    }

    /** 恢复被暂时下架的活动：按自然时间轴取回状态（RECRUITING/IN_PROGRESS/ENDED），并恢复其群聊。 */
    private void resumeActivity(VolunteerActivity activity, LocalDateTime now) {
        String next = activity.getStartsAt().isAfter(now) ? "RECRUITING"
                : activity.getEndsAt().isAfter(now) ? "IN_PROGRESS" : "ENDED";
        activityMapper.update(null, Wrappers.<VolunteerActivity>lambdaUpdate()
                .set(VolunteerActivity::getStatus, next)
                .setSql("version = version + 1")
                .eq(VolunteerActivity::getId, activity.getId())
                .eq(VolunteerActivity::getStatus, "SUSPENDED"));
        ActivityGroup group = groupMapper.selectOne(Wrappers.<ActivityGroup>lambdaQuery()
                .eq(ActivityGroup::getActivityId, activity.getId())
                .eq(ActivityGroup::getStatus, "SUSPENDED"));
        if (group != null) {
            groupService.resumeGroupById(group.getId());
        }
    }

    private List<VolunteerActivity> activitiesOf(String activityId) {
        if (activityId == null) {
            return List.of();
        }
        VolunteerActivity activity = activityMapper.selectById(activityId);
        return activity == null ? List.of() : List.of(activity);
    }

    private void banOrg(String orgId, Integer durationDays, String penaltyId, String adminId, String reason) {
        ensureRestriction(null, orgId, AbuseGuardService.KIND_ORG_BANNED, penaltyId, adminId,
                reason, durationDays == null ? null : LocalDateTime.now().plusDays(durationDays));
    }

    /** 禁止发布活动：只挡新建/重新发布；到期读时失效（expire 落在 restriction.expires_at 上）。 */
    private void banPublish(String orgId, Integer durationDays, String penaltyId, String adminId, String reason) {
        if (orgId == null) {
            return;
        }
        ensureRestriction(null, orgId, AbuseGuardService.KIND_PUBLISH_BANNED, penaltyId, adminId,
                reason, durationDays == null ? null : LocalDateTime.now().plusDays(durationDays));
    }

    /** 活动类举报的机构归属：举报行缺 orgId 时回查活动行。 */
    private String orgIdOfActivity(String activityId, String fallbackOrgId) {
        if (fallbackOrgId != null) {
            return fallbackOrgId;
        }
        if (activityId == null) {
            return null;
        }
        VolunteerActivity activity = activityMapper.selectById(activityId);
        return activity == null ? null : activity.getOrganizationId();
    }

    private void banUser(String userId, Integer durationDays, String penaltyId, String adminId, String reason) {
        ensureRestriction(userId, null, AbuseGuardService.KIND_ACCOUNT_BANNED, penaltyId, adminId,
                reason, durationDays == null ? null : LocalDateTime.now().plusDays(durationDays));
        // 封禁连带禁用账户（登录被拒），申诉推翻时按 account_disabled 恢复。
        userAccountMapper.update(null, Wrappers.<UserAccount>lambdaUpdate()
                .set(UserAccount::getStatus, "DISABLED")
                .setSql("version = version + 1")
                .eq(UserAccount::getId, userId)
                .eq(UserAccount::getStatus, "ENABLED"));
        enrollmentService.kickFromAllActiveActivities(userId, "账户被封禁，已移出未结束活动");
    }

    private void ensureRestriction(String userId, String orgId, String kind, String penaltyId,
                                   String adminId, String reason, LocalDateTime expiresAt) {
        boolean exists = restrictionMapper.selectCount(Wrappers.<AbuseRestriction>lambdaQuery()
                .eq(userId != null, AbuseRestriction::getUserId, userId)
                .eq(orgId != null, AbuseRestriction::getOrgId, orgId)
                .eq(AbuseRestriction::getKind, kind)
                .eq(AbuseRestriction::getStatus, "ACTIVE")) > 0;
        if (exists) {
            return;
        }
        AbuseRestriction restriction = new AbuseRestriction();
        restriction.setId(IdGenerator.next());
        restriction.setStatus("ACTIVE");
        restriction.setKind(kind);
        restriction.setUserId(userId);
        restriction.setOrgId(orgId);
        restriction.setPenaltyId(penaltyId);
        restriction.setReason(reason);
        restriction.setExpiresAt(expiresAt);
        restriction.setCreatedBy(adminId);
        restrictionMapper.insert(restriction);
    }

    // ---------- 恶意举报阈值升级 ----------

    /**
     * 评估举报人：近 {@code windowDays} 天内发起的举报里，被驳回的数量与占比决定升级——
     * 到 lockThreshold 且驳回占比 ≥ dismissRatio → 锁举报；
     * 到 activityBanThreshold → 禁止参加活动并踢出未结束活动；
     * 到 accountBanThreshold → 封禁账户。阶梯只升不降，各类限制幂等。
     */
    public void evaluateReporter(String reporterUid) {
        if (reporterUid == null || reporterUid.isBlank()) {
            return;
        }
        LocalDateTime since = LocalDateTime.now().minusDays(windowDays);
        List<AbuseReport> mine = reportMapper.selectList(Wrappers.<AbuseReport>lambdaQuery()
                .eq(AbuseReport::getReporterUid, reporterUid)
                .ge(AbuseReport::getCreatedAt, since));
        long total = mine.size();
        long dismissed = mine.stream().filter(r -> "DISMISSED".equals(r.getStatus())).count();
        if (dismissed >= accountBanThreshold) {
            applyReporterRestriction(reporterUid, AbuseGuardService.KIND_ACCOUNT_BANNED, dismissed, total);
            return;
        }
        if (dismissed >= activityBanThreshold) {
            applyReporterRestriction(reporterUid, AbuseGuardService.KIND_ACTIVITY_BANNED, dismissed, total);
            return;
        }
        if (total >= lockThreshold && dismissed > 0 && (double) dismissed / total >= dismissRatio) {
            applyReporterRestriction(reporterUid, AbuseGuardService.KIND_REPORT_LOCKED, dismissed, total);
        }
    }

    private void applyReporterRestriction(String uid, String kind, long dismissed, long total) {
        ensureRestriction(uid, null, kind, null, null,
                "近" + windowDays + "天举报 " + total + " 次，其中 " + dismissed + " 次被驳回（自动升级限制）", null);
        if (AbuseGuardService.KIND_ACCOUNT_BANNED.equals(kind)) {
            userAccountMapper.update(null, Wrappers.<UserAccount>lambdaUpdate()
                    .set(UserAccount::getStatus, "DISABLED")
                    .setSql("version = version + 1")
                    .eq(UserAccount::getId, uid)
                    .eq(UserAccount::getStatus, "ENABLED"));
        }
        if (AbuseGuardService.KIND_ACCOUNT_BANNED.equals(kind)
                || AbuseGuardService.KIND_ACTIVITY_BANNED.equals(kind)) {
            enrollmentService.kickFromAllActiveActivities(uid, "账户被限制参加活动，已移出未结束活动");
        }
        String title = AbuseGuardService.KIND_ACCOUNT_BANNED.equals(kind) ? "账户已被封禁"
                : AbuseGuardService.KIND_ACTIVITY_BANNED.equals(kind) ? "你已被限制参加活动" : "举报功能已被锁定";
        notificationService.notify(uid, "USER", NotificationService.TYPE_PENALTY_APPLIED, title,
                "近期发起的举报中大量被驳回（" + dismissed + "/" + total + "），系统已自动升级限制。",
                "/volunteer/penalties");
    }

    // ---------- 装配与校验 ----------

    private void requireTargetExists(String category, String targetId) {
        boolean exists = switch (category) {
            case CATEGORY_ACTIVITY -> activityMapper.selectById(targetId) != null;
            case CATEGORY_ORGANIZATION -> organizationMapper.selectById(targetId) != null;
            case CATEGORY_GROUP -> groupMapper.selectById(targetId) != null;
            case CATEGORY_USER -> userAccountMapper.selectById(targetId) != null;
            default -> false;
        };
        if (!exists) {
            throw new ApiException(HttpStatus.NOT_FOUND, "举报对象不存在");
        }
    }

    private void applyTarget(AbuseReport report, String category, String targetId) {
        switch (category) {
            case CATEGORY_ACTIVITY -> report.setTargetActivityId(targetId);
            case CATEGORY_ORGANIZATION -> report.setTargetOrgId(targetId);
            case CATEGORY_GROUP -> report.setTargetGroupId(targetId);
            case CATEGORY_USER -> report.setTargetUserId(targetId);
            default -> throw new ApiException(HttpStatus.BAD_REQUEST, "不支持的举报类别");
        }
    }

    private com.baomidou.mybatisplus.core.toolkit.support.SFunction<AbuseReport, Object> targetColumn(String category) {
        return switch (category) {
            case CATEGORY_ACTIVITY -> AbuseReport::getTargetActivityId;
            case CATEGORY_ORGANIZATION -> AbuseReport::getTargetOrgId;
            case CATEGORY_GROUP -> AbuseReport::getTargetGroupId;
            case CATEGORY_USER, CATEGORY_REPORT_ABUSE -> AbuseReport::getTargetUserId;
            default -> AbuseReport::getTargetUserId;
        };
    }

    private com.baomidou.mybatisplus.core.toolkit.support.SFunction<Penalty, Object> penaltyTargetColumn(String category) {
        return switch (category) {
            case CATEGORY_ACTIVITY -> Penalty::getTargetActivityId;
            case CATEGORY_ORGANIZATION -> Penalty::getTargetOrgId;
            default -> Penalty::getTargetUserId;
        };
    }

    private AbuseReport requireReport(String reportId) {
        AbuseReport report = reportMapper.selectById(reportId);
        if (report == null) {
            throw new ApiException(HttpStatus.NOT_FOUND, "举报不存在");
        }
        return report;
    }

    private PresetPenalty requirePreset(String presetId) {
        PresetPenalty preset = presetMapper.selectById(presetId);
        if (preset == null) {
            throw new ApiException(HttpStatus.NOT_FOUND, "预设处罚不存在");
        }
        return preset;
    }

    private Penalty requirePenalty(String penaltyId) {
        Penalty penalty = penaltyMapper.selectById(penaltyId);
        if (penalty == null) {
            throw new ApiException(HttpStatus.NOT_FOUND, "处罚不存在");
        }
        return penalty;
    }

    private Map<String, String> loginNamesOf(java.util.Set<String> uids) {
        if (uids == null || uids.isEmpty()) {
            return Map.of();
        }
        return userAccountMapper.selectBatchIds(uids).stream()
                .collect(Collectors.toMap(UserAccount::getId, UserAccount::getLoginName, (a, b) -> a));
    }

    private String categoryLabel(String category) {
        return switch (category) {
            case CATEGORY_ACTIVITY -> "活动";
            case CATEGORY_ORGANIZATION -> "机构";
            case CATEGORY_GROUP -> "群聊";
            case CATEGORY_USER -> "用户";
            case CATEGORY_REPORT_ABUSE -> "举报";
            default -> category;
        };
    }

    private String targetSummary(AbuseReport report) {
        if (report.getTargetActivityId() != null) {
            VolunteerActivity activity = activityMapper.selectById(report.getTargetActivityId());
            return activity == null ? report.getTargetActivityId() : activity.getTitle();
        }
        if (report.getTargetOrgId() != null) {
            Organization organization = organizationMapper.selectById(report.getTargetOrgId());
            return organization == null ? report.getTargetOrgId() : organization.getPublicName();
        }
        if (report.getTargetGroupId() != null) {
            ActivityGroup group = groupMapper.selectById(report.getTargetGroupId());
            if (group == null) {
                return report.getTargetGroupId();
            }
            VolunteerActivity activity = activityMapper.selectById(group.getActivityId());
            return activity == null ? report.getTargetGroupId() : "群聊：" + activity.getTitle();
        }
        if (report.getTargetUserId() != null) {
            UserAccount account = userAccountMapper.selectById(report.getTargetUserId());
            return account == null ? report.getTargetUserId() : "用户：" + account.getLoginName();
        }
        return null;
    }

    private ReportResponse toReportResponse(AbuseReport report) {
        return new ReportResponse(report.getId(), report.getCategory(), report.getStatus(), report.getReason(),
                report.getDecisionNote(), report.getHandledAt(), report.getCreatedAt());
    }

    private AdminReportResponse toAdminResponse(AbuseReport report, Map<String, String> loginNames) {
        return new AdminReportResponse(report.getId(), report.getCategory(), report.getStatus(),
                report.getReporterUid(), loginNames.getOrDefault(report.getReporterUid(), report.getReporterUid()),
                report.getReason(), report.getTargetActivityId(), report.getTargetOrgId(),
                report.getTargetGroupId(), report.getTargetUserId(), targetSummary(report),
                report.getSourceReportId(), report.getHandledBy(), report.getHandledAt(),
                report.getDecisionNote(), report.getPenaltyId(), report.getCreatedAt());
    }

    private AppealResponse toAppealResponse(Appeal appeal) {
        return new AppealResponse(appeal.getId(), appeal.getPenaltyId(), appeal.getAppellantType(),
                appeal.getStatus(), appeal.getReason(), appeal.getDecisionNote(), appeal.getHandledAt(),
                appeal.getCreatedAt());
    }

    private PenaltyResponse toPenaltyResponse(Penalty penalty) {
        PresetPenalty preset = presetMapper.selectById(penalty.getPresetId());
        boolean appealed = appealMapper.selectCount(Wrappers.<Appeal>lambdaQuery()
                .eq(Appeal::getPenaltyId, penalty.getId())) > 0;
        return new PenaltyResponse(penalty.getId(), penalty.getCategory(), penalty.getAction(),
                preset == null ? null : preset.getName(), penalty.getDurationDays(), penalty.getReason(),
                penalty.getStatus(), penalty.getAppliedAt(), penalty.getExpiresAt(),
                "ACTIVE".equals(penalty.getStatus()) && !appealed);
    }
}
