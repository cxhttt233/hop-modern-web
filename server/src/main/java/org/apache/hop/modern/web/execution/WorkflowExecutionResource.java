package org.apache.hop.modern.web.execution;

import jakarta.ws.rs.GET;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;
import org.apache.hop.workflow.WorkflowMeta;

@Path("/api/v2")
@Produces(MediaType.APPLICATION_JSON)
public final class WorkflowExecutionResource implements AutoCloseable {
  private final WorkflowDocumentSource documents;
  private final WorkflowExecutionAdapter executions;

  public WorkflowExecutionResource(WorkflowDocumentSource documents, WorkflowExecutionAdapter executions) {
    this.documents = Objects.requireNonNull(documents, "documents");
    this.executions = Objects.requireNonNull(executions, "executions");
  }

  @POST
  @Path("/documents/{documentId}/executions")
  public Response start(@PathParam("documentId") String documentId) {
    Optional<WorkflowMeta> snapshot = documents.executionSnapshot(documentId);
    if (snapshot.isEmpty()) return error(404, "document_not_found", "opened workflow document was not found");
    try {
      String id = executions.start(documentId, snapshot.orElseThrow());
      return Response.accepted(executions.status(id).map(WorkflowExecutionResource::toStatus).orElseThrow()).build();
    } catch (RuntimeException e) {
      return error(422, "execution_start_failed",
          e.getMessage() == null ? "workflow execution could not be started" : e.getMessage());
    }
  }

  @GET
  @Path("/executions/{executionId}")
  public Response status(@PathParam("executionId") String executionId) {
    var snapshot = executions.status(executionId).orElse(null);
    return snapshot == null ? error(404, "execution_not_found", "workflow execution was not found")
        : Response.ok(toStatus(snapshot)).build();
  }

  @POST
  @Path("/executions/{executionId}/stop")
  public Response stop(@PathParam("executionId") String executionId) {
    var snapshot = executions.stop(executionId).orElse(null);
    return snapshot == null ? error(404, "execution_not_found", "workflow execution was not found")
        : Response.ok(toStatus(snapshot)).build();
  }

  private static ExecutionStatus toStatus(WorkflowExecutionAdapter.Snapshot s) {
    return new ExecutionStatus(s.id(), s.owner(), s.state().name().toLowerCase(Locale.ROOT),
        s.createdAt(), s.completedAt(), s.errors(), s.stopped());
  }

  private static Response error(int status, String code, String message) {
    return Response.status(status).entity(new ErrorResponse(code, message)).build();
  }

  @Override public void close() { executions.close(); }

  @FunctionalInterface
  public interface WorkflowDocumentSource {
    Optional<WorkflowMeta> executionSnapshot(String documentId);
  }

  public record ExecutionStatus(String id, String documentId, String state,
      java.time.Instant startTime, java.time.Instant endTime, long errors, boolean stopped) {}
  public record ErrorResponse(String code, String message) {}
}
