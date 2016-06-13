package org.rabix.engine.rest.api.impl;

import java.util.Collections;

import javax.ws.rs.core.Response;
import javax.ws.rs.core.Response.Status;

import org.rabix.bindings.model.Job;
import org.rabix.engine.rest.api.JobHTTPService;
import org.rabix.engine.rest.service.JobServiceException;
import org.rabix.engine.rest.service.EngineRestServiceException;
import org.rabix.engine.rest.service.JobService;

import com.google.inject.Inject;

public class JobHTTPServiceImpl implements JobHTTPService {

  private final JobService jobService;

  @Inject
  public JobHTTPServiceImpl(JobService jobService) {
    this.jobService = jobService;
  }
  
  @Override
  public Response create(Job job) {
    try {
      return ok(jobService.create(job));
    } catch (Exception e) {
      return error();
    }
  }
  
  @Override
  public Response get() {
    try {
      return ok(jobService.get());
    } catch (EngineRestServiceException e) {
      return error();
    }
  }
  
  @Override
  public Response get(String id) {
    try {
      Job job = jobService.get(id);
      
      if (job == null) {
        return entityNotFound();
      }
      return ok(job);
    } catch (EngineRestServiceException e) {
      return error();
    }
  }
  
  @Override
  public Response save(String id, Job job) {
    try {
      jobService.update(job);
    } catch (JobServiceException e) {
      return error();
    }
    return ok();
  }
  
  private Response entityNotFound() {
    return Response.status(Status.NOT_FOUND).build();
  }
  
  private Response error() {
    return Response.status(Status.BAD_REQUEST).build();
  }
  
  private Response ok() {
    return Response.ok(Collections.emptyMap()).build();
  }
  
  private Response ok(Object items) {
    if (items == null) {
      return ok();
    }
    return Response.ok().entity(items).build();
  }
}
