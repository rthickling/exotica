package lab;

import com.fasterxml.jackson.databind.JsonNode;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import org.springframework.web.bind.annotation.*;
import java.time.LocalDate;
import java.util.*;

@RestController
@RequestMapping("/api")
public class ApiController {
    private final WorkflowService workflow;
    private final ProductCatalog catalog;

    ApiController(WorkflowService workflow, ProductCatalog catalog) {
        this.workflow = workflow;
        this.catalog = catalog;
    }

    public record CreateRequest(@NotBlank String product, @NotBlank String clientId, @NotBlank String operatorId) {
    }

    public record RfqRequest(Map<String, String> scenarios, Integer deadlineMillis) {
    }

    public record MatchRequest(@NotBlank String outcome) {
    }

    public record SelectionRequest(@NotNull UUID offerId, @NotBlank @Size(max = 2000) String rationale) {
    }

    public record InstructionRequest(boolean authorised, @NotBlank @Size(max = 200) String reference) {
    }

    public record FirmRequest(@NotBlank String scenario) {
    }

    public record AcceptRequest(@NotNull UUID offerId, @NotBlank @Size(max = 200) String idempotencyKey,
            boolean failAfterTrade, Long expectedVersion) {
    }

    public record RevisionRequest(@NotNull JsonNode terms) {
    }

    public record LifecycleRequest(@NotNull JsonNode fixings) {
    }

    public record AdvanceRequest(@NotNull LocalDate asOf, boolean settle) {
    }

    @GetMapping("/templates")
    Object templates() {
        return catalog.all();
    }

    @PostMapping("/enquiries")
    Object create(@Valid @RequestBody CreateRequest request) {
        return workflow.create(request);
    }

    @GetMapping("/enquiries/{id}")
    Object get(@PathVariable UUID id) {
        return workflow.getEnquiry(id);
    }

    @PostMapping("/enquiries/{id}/revision")
    Object revision(@PathVariable UUID id, @Valid @RequestBody RevisionRequest request) {
        return workflow.revise(id, request.terms());
    }

    @PostMapping("/enquiries/{id}/rfq")
    Object rfq(@PathVariable UUID id, @RequestBody RfqRequest request) {
        return workflow.rfq(id, request);
    }

    @GetMapping("/enquiries/{id}/comparison")
    Object compare(@PathVariable UUID id) {
        return workflow.compare(id);
    }

    @PostMapping("/enquiries/{id}/match")
    Object match(@PathVariable UUID id, @Valid @RequestBody MatchRequest request) {
        return workflow.match(id, request.outcome());
    }

    @PostMapping("/enquiries/{id}/selection")
    Object select(@PathVariable UUID id, @Valid @RequestBody SelectionRequest request) {
        return workflow.select(id, request.offerId(), request.rationale());
    }

    @PostMapping("/enquiries/{id}/instruction")
    Object instruction(@PathVariable UUID id, @Valid @RequestBody InstructionRequest request) {
        return workflow.instruct(id, request);
    }

    @PostMapping("/enquiries/{id}/firm")
    Object firm(@PathVariable UUID id, @Valid @RequestBody FirmRequest request) {
        return workflow.firm(id, request.scenario());
    }

    @PostMapping("/enquiries/{id}/accept")
    Object accept(@PathVariable UUID id, @Valid @RequestBody AcceptRequest request) {
        try {
            return workflow.accept(id, request);
        } catch (org.springframework.dao.DataIntegrityViolationException
                | org.springframework.dao.OptimisticLockingFailureException conflict) {
            // A retry racing the original request may lose the INSERT but still has the
            // same result.
            // Recovery runs in a fresh read transaction after the failed transaction is
            // rolled back.
            return workflow.recoverAcceptance(id, request).orElseThrow(() -> conflict);
        }
    }

    @PostMapping("/enquiries/{id}/value")
    Object value(@PathVariable UUID id, @RequestBody JsonNode request) {
        return workflow.value(id, request);
    }

    @GetMapping("/trades/{id}")
    Object trade(@PathVariable UUID id) {
        return workflow.getTrade(id);
    }

    @PostMapping("/trades/{id}/lifecycle")
    Object lifecycle(@PathVariable UUID id, @Valid @RequestBody LifecycleRequest request) {
        return workflow.lifecycle(id, request.fixings());
    }

    @PostMapping("/trades/{id}/obligations/advance")
    Object advance(@PathVariable UUID id, @Valid @RequestBody AdvanceRequest request) {
        return workflow.advance(id, request.asOf(), request.settle());
    }
}
