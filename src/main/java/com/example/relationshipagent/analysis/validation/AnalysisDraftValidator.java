package com.example.relationshipagent.analysis.validation;

import com.example.relationshipagent.analysis.agent.AnalysisDraft;
import com.example.relationshipagent.analysis.agent.AnalysisPromptFactory;
import com.example.relationshipagent.analysis.evidence.EvidenceKind;
import com.example.relationshipagent.analysis.evidence.EvidencePacket;
import com.example.relationshipagent.analysis.evidence.EvidenceRef;
import com.example.relationshipagent.analysis.evidence.EvidenceRole;
import org.springframework.stereotype.Component;

import java.util.*;
import java.util.regex.Pattern;

/**
 * Validates an untrusted draft against the exact server-resolved evidence manifest.
 */
@Component
public class AnalysisDraftValidator {
    private static final Pattern ABSOLUTE = Pattern.compile("总是|从不|显然|就是因为");
    private final ClaimConfidenceCalibrator calibrator = new ClaimConfidenceCalibrator();

    public ValidationResult validate(AnalysisDraft draft, List<EvidencePacket> packets) {
        Manifest manifest = Manifest.from(packets);
        List<String> reportErrors = new ArrayList<>();
        if (draft == null || !AnalysisPromptFactory.SCHEMA_VERSION.equals(draft.schemaVersion()))
            reportErrors.add("INVALID_SCHEMA_VERSION");
        Set<String> sections = new HashSet<>();
        List<ValidatedClaim> claims = new ArrayList<>();
        if (draft != null && draft.sections() != null)
            for (AnalysisDraft.AnalysisSectionDraft section : draft.sections()) {
                if (!AnalysisPromptFactory.SECTION_KEYS.contains(section.sectionKey()) || !sections.add(section.sectionKey())) {
                    reportErrors.add("INVALID_OR_DUPLICATE_SECTION:" + section.sectionKey());
                    continue;
                }
                List<AnalysisDraft.AnalysisClaimDraft> sectionClaims = section.claims() == null ? List.of() : section.claims();
                for (AnalysisDraft.AnalysisClaimDraft claim : sectionClaims)
                    claims.add(validateClaim(section.sectionKey(), claim, manifest));
            }
        if (!sections.containsAll(AnalysisPromptFactory.SECTION_KEYS)) reportErrors.add("MISSING_REQUIRED_SECTIONS");
        if (draft == null || draft.limitations() == null || draft.limitations().isEmpty())
            reportErrors.add("MISSING_LIMITATIONS");
        return new ValidationResult(List.copyOf(claims), List.copyOf(reportErrors));
    }

    private ValidatedClaim validateClaim(String section, AnalysisDraft.AnalysisClaimDraft claim, Manifest manifest) {
        List<String> errors = new ArrayList<>();
        if (claim == null || blank(claim.claimKey()) || blank(claim.statement()) || !Set.of("FACT", "INFERENCE", "HYPOTHESIS").contains(claim.claimType()))
            errors.add("INVALID_CLAIM_FIELDS");
        if (claim == null) return new ValidatedClaim(section, null, List.of(), List.of(), 0d, "REJECTED", errors);
        List<EvidenceRef> support = resolve(claim.supportEvidenceRefIds(), EvidenceRole.SUPPORT, manifest, errors);
        List<EvidenceRef> counter = resolve(claim.counterEvidenceRefIds(), EvidenceRole.COUNTER, manifest, errors);
        Set<String> overlap = new HashSet<>(safeIds(claim.supportEvidenceRefIds()));
        overlap.retainAll(safeIds(claim.counterEvidenceRefIds()));
        if (!overlap.isEmpty()) errors.add("SUPPORT_COUNTER_OVERLAP");
        if ("FACT".equals(claim.claimType()) && support.isEmpty()) errors.add("FACT_REQUIRES_DIRECT_SUPPORT");
        if ("INFERENCE".equals(claim.claimType()) && support.size() < 2 && !hasStatisticAndMessage(support))
            errors.add("INFERENCE_REQUIRES_TWO_SUPPORTS");
        if (("RELATIONSHIP_ENDING".equals(section) || "POSSIBLE_FACTORS".equals(section)) && "INFERENCE".equals(claim.claimType())
                && counter.isEmpty() && blank(claim.uncertaintyNote()))
            errors.add("ENDING_OR_FACTOR_REQUIRES_COUNTER_OR_UNCERTAINTY");
        if ("RELATIONSHIP_ENDING".equals(section) && support.stream().anyMatch(ref -> !manifest.terminalIds.contains(ref.evidenceRefId())))
            errors.add("ENDING_REQUIRES_TERMINAL_EVIDENCE");
        if (ABSOLUTE.matcher(claim.statement()).find()) errors.add("ABSOLUTE_WORDING_REQUIRES_REVIEW");
        String status = errors.stream().anyMatch(error -> error.startsWith("UNKNOWN_") || error.startsWith("INVALID_") || error.contains("REQUIRES") || error.equals("SUPPORT_COUNTER_OVERLAP")) ? "REJECTED" : errors.isEmpty() ? "VALID" : "REVIEW_REQUIRED";
        return new ValidatedClaim(section, claim, support, counter, calibrator.calibrate(claim.claimType(), claim.confidence(), support, counter), status, List.copyOf(errors));
    }

    private List<EvidenceRef> resolve(List<String> ids, EvidenceRole role, Manifest manifest, List<String> errors) {
        List<EvidenceRef> result = new ArrayList<>();
        for (String id : safeIds(ids)) {
            EvidenceRef ref = manifest.refs.get(id);
            if (ref == null) errors.add("UNKNOWN_EVIDENCE_REF:" + id);
            else if (ref.suggestedRole() != role) errors.add("INVALID_EVIDENCE_ROLE:" + id);
            else result.add(ref);
        }
        return result;
    }

    private static boolean hasStatisticAndMessage(List<EvidenceRef> refs) {
        return refs.stream().anyMatch(r -> r.kind() == EvidenceKind.STATISTIC) && refs.stream().anyMatch(r -> r.kind() == EvidenceKind.MESSAGE);
    }

    private static List<String> safeIds(List<String> ids) {
        return ids == null ? List.of() : ids;
    }

    private static boolean blank(String value) {
        return value == null || value.isBlank();
    }

    public record ValidatedClaim(String sectionKey, AnalysisDraft.AnalysisClaimDraft draft, List<EvidenceRef> support,
                                 List<EvidenceRef> counter, double confidence, String status, List<String> errors) {
    }

    public record ValidationResult(List<ValidatedClaim> claims, List<String> reportErrors) {
        public boolean publishable() {
            return reportErrors.isEmpty() && claims.stream().anyMatch(c -> "VALID".equals(c.status()));
        }
    }

    private static class Manifest {
        final Map<String, EvidenceRef> refs = new LinkedHashMap<>();
        final Set<String> terminalIds = new HashSet<>();

        static Manifest from(List<EvidencePacket> packets) {
            Manifest m = new Manifest();
            for (EvidencePacket p : packets)
                for (EvidenceRef r : concat(p)) {
                    m.refs.put(r.evidenceRefId(), r);
                    if ("TERMINAL".equals(p.packetType())) m.terminalIds.add(r.evidenceRefId());
                }
            return m;
        }

        private static List<EvidenceRef> concat(EvidencePacket p) {
            List<EvidenceRef> refs = new ArrayList<>(p.supportCandidates());
            refs.addAll(p.counterCandidates());
            return refs;
        }
    }
}
