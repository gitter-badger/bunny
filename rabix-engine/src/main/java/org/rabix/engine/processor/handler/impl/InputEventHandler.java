package org.rabix.engine.processor.handler.impl;

import java.util.ArrayList;
import java.util.List;

import org.rabix.bindings.BindingException;
import org.rabix.bindings.model.dag.DAGContainer;
import org.rabix.bindings.model.dag.DAGLink;
import org.rabix.bindings.model.dag.DAGLinkPort;
import org.rabix.bindings.model.dag.DAGLinkPort.LinkPortType;
import org.rabix.bindings.model.dag.DAGNode;
import org.rabix.bindings.model.dag.DAGNode.LinkMerge;
import org.rabix.bindings.model.dag.DAGNode.ScatterMethod;
import org.rabix.common.helper.InternalSchemaHelper;
import org.rabix.engine.JobHelper;
import org.rabix.engine.db.DAGNodeDB;
import org.rabix.engine.event.Event;
import org.rabix.engine.event.impl.InputUpdateEvent;
import org.rabix.engine.model.JobRecord;
import org.rabix.engine.model.LinkRecord;
import org.rabix.engine.model.VariableRecord;
import org.rabix.engine.model.scatter.RowMapping;
import org.rabix.engine.model.scatter.ScatterMapping;
import org.rabix.engine.model.scatter.impl.ScatterCartesianMapping;
import org.rabix.engine.model.scatter.impl.ScatterOneToOneMapping;
import org.rabix.engine.processor.EventProcessor;
import org.rabix.engine.processor.handler.EventHandler;
import org.rabix.engine.processor.handler.EventHandlerException;
import org.rabix.engine.service.JobService;
import org.rabix.engine.service.JobService.JobState;
import org.rabix.engine.service.LinkService;
import org.rabix.engine.service.VariableService;

import com.google.inject.Inject;

/**
 * Handles {@link InputUpdateEvent} events.
 */
public class InputEventHandler implements EventHandler<InputUpdateEvent> {

  private final DAGNodeDB nodeDB;
  private final JobService jobService;
  private final LinkService linkService;
  private final VariableService variableService;
  private final EventProcessor eventProcessor;

  @Inject
  public InputEventHandler(EventProcessor eventProcessor, JobService jobService, VariableService variableService, LinkService linkService, DAGNodeDB nodeDB) {
    this.nodeDB = nodeDB;
    this.jobService = jobService;
    this.linkService = linkService;
    this.variableService = variableService;
    this.eventProcessor = eventProcessor;
  }

  public void handle(InputUpdateEvent event) throws EventHandlerException {
    JobRecord job = jobService.find(event.getJobId(), event.getContextId());
    VariableRecord variable = variableService.find(event.getJobId(), event.getPortId(), LinkPortType.INPUT, event.getContextId());

    DAGNode node = nodeDB.get(InternalSchemaHelper.normalizeId(job.getId()), event.getContextId());
    if (!job.isInputPortReady(event.getPortId()) && !event.isEventFromScatter()) {
      variable.addValue(event.getValue(), node.getLinkMerge());
      job.decrementPortCounter(event.getPortId(), LinkPortType.INPUT);
    }
    
    // TODO REFACTOR
    if (!job.isScattered()) {
      if (job.isScatterPort(event.getPortId()) && (!LinkMerge.isBlocking(node.getLinkMerge()) || event.isScatteringInPlace())) {
        scatterPort(job, event);
        return;
      } else {
        if (job.isScatterWrapper()) {
          sendValuesToScatteredJobs(job, variable, event);
          return;
        } else {
          if (event.isEventFromLookAhead()) {
            job.resetInputPortCounters(event.getScatteredNodes());
          }
          if (event.isEventFromScatter()) {
            variable.addValue(event.getValue(), node.getLinkMerge());
            job.decrementPortCounter(event.getPortId(), LinkPortType.INPUT);

            if (job.isInputPortReady(event.getPortId()) && job.isScatterPort(event.getPortId())) {
              scatterPort(job, event);
              return;
            }
          }
        }
      }
    }

    if (job.isReady()) {
      ready(job, event);
    }

    jobService.update(job);
    variableService.update(variable);
  }

  /**
   * Job is ready
   */
  private void ready(JobRecord job, InputUpdateEvent event) throws EventHandlerException {
    job.setState(JobState.READY);

    if (job.isContainer()) {
      job.setState(JobState.RUNNING);

      DAGContainer containerNode;
      if (job.isScattered()) {
        containerNode = (DAGContainer) nodeDB.get(InternalSchemaHelper.getJobIdFromScatteredId(job.getId()), event.getContextId());
      } else {
        containerNode = (DAGContainer) nodeDB.get(job.getId(), event.getContextId());
      }
      rollOutContainer(job, containerNode, event.getContextId());

      List<LinkRecord> containerLinks = linkService.findBySourceAndSourceType(event.getJobId(), LinkPortType.INPUT, event.getContextId());
      for (LinkRecord link : containerLinks) {
        VariableRecord sourceVariable = variableService.find(link.getSourceJobId(), link.getSourceJobPort(), LinkPortType.INPUT, event.getContextId());
        VariableRecord destinationVariable = variableService.find(link.getDestinationJobId(), link.getDestinationJobPort(), LinkPortType.INPUT, event.getContextId());

        Event updateEvent = new InputUpdateEvent(event.getContextId(), destinationVariable.getJobId(), destinationVariable.getPortId(), sourceVariable.getValue());
        eventProcessor.send(updateEvent);
      }
    }
  }

  /**
   * Scatters port
   */
  @SuppressWarnings("unchecked")
  private void scatterPort(JobRecord job, InputUpdateEvent event) throws EventHandlerException {
    DAGNode node = nodeDB.get(InternalSchemaHelper.normalizeId(job.getId()), event.getContextId());

    if (job.getScatterMapping() == null) {
      job.setScatterMapping(getScatterMapping(node));
    }
    
    int numberOfScattered = getNumberOfScattered(job, event);
    if (!event.isScatteringInPlace()) {
      VariableRecord variable = variableService.find(job.getId(), event.getPortId(), LinkPortType.INPUT, event.getContextId());
      
      if (event.isEventFromLookAhead() && !LinkMerge.isBlocking(node.getLinkMerge())) {
        job.resetInputPortCounters(numberOfScattered);
        job.decrementPortCounter(event.getPortId(), LinkPortType.INPUT);

        variable.addValue(event.getValue(), node.getLinkMerge());
        variableService.update(variable);
      } else {
        job.setScatterWrapper(true);
        jobService.update(job);

        Object value = null;
        List<Object> values = null;
        if (job.isInputPortReady(event.getPortId())) {
          value = variable.getValue();
        } else {
          value = event.getValue();
        }
        if (value instanceof List<?>) {
          values = (List<Object>) value;
        } else {
          values = new ArrayList<>();
          values.add(value);
        }
        for (Object subvalue : values) {
          eventProcessor.send(new InputUpdateEvent(event.getContextId(), job.getId(), event.getPortId(), subvalue, true, true, values.size(), true));
        }
        return;
      }
    }

    ScatterMapping scatterMapping = job.getScatterMapping();
    scatterMapping.enable(event.getPortId(), event.getValue());
    
    List<RowMapping> mappings = null;
    try {
      mappings = scatterMapping.getEnabledRows();
    } catch (BindingException e) {
      throw new EventHandlerException("Failed to enable ScatterMapping for node " + node.getId(), e);
    }
    scatterMapping.commit(mappings);
    
    for (RowMapping mapping : mappings) {
      job.setState(JobState.RUNNING);
      jobService.update(job);

      List<Event> events = new ArrayList<>();

      String jobNId = InternalSchemaHelper.scatterId(job.getId(), mapping.getIndex());
      JobRecord jobN = new JobRecord(event.getContextId(), jobNId, JobHelper.generateUniqueId(), JobState.PENDING, job.isContainer(), true, false);

      for (DAGLinkPort inputPort : node.getInputPorts()) {
        VariableRecord variableN = new VariableRecord(event.getContextId(), jobNId, inputPort.getId(), LinkPortType.INPUT, null);
        variableN.setNumberGlobals(numberOfScattered);
        variableService.create(variableN);

        if (jobN.getState().equals(JobState.PENDING)) {
          jobN.incrementPortCounter(inputPort, LinkPortType.INPUT);
        }
        LinkRecord link = new LinkRecord(event.getContextId(), job.getId(), inputPort.getId(), LinkPortType.INPUT, jobNId, inputPort.getId(), LinkPortType.INPUT);
        linkService.create(link);

        if (inputPort.isScatter()) {
          Event eventInputPort = new InputUpdateEvent(event.getContextId(), jobNId, inputPort.getId(), mapping.getValue(inputPort.getId()));
          events.add(eventInputPort);
        } else {
          if (job.isInputPortReady(inputPort.getId())) {
            VariableRecord variable = variableService.find(job.getId(), inputPort.getId(), LinkPortType.INPUT, event.getContextId());
            events.add(new InputUpdateEvent(event.getContextId(), jobNId, inputPort.getId(), variable.getValue()));
          }
        }
      }
      for (DAGLinkPort outputPort : node.getOutputPorts()) {
        VariableRecord variableN = new VariableRecord(event.getContextId(), jobNId, outputPort.getId(), LinkPortType.OUTPUT, null);
        variableN.setNumberGlobals(numberOfScattered);
        variableService.create(variableN);
        jobN.incrementPortCounter(outputPort, LinkPortType.OUTPUT);

        LinkRecord link = new LinkRecord(event.getContextId(), jobNId, outputPort.getId(), LinkPortType.OUTPUT, job.getId(), outputPort.getId(), LinkPortType.OUTPUT);
        linkService.create(link);
      }

      job.setState(JobState.RUNNING);
      job.setScatterWrapper(true);
      
      job.resetOutputPortCounters(getNumberOfScattered(job, event));
      jobService.update(job);
      jobService.create(jobN);

      for (Event subevent : events) {
        eventProcessor.send(subevent);
      }
    }
  }
  
  public ScatterMapping getScatterMapping(DAGNode dagNode) throws EventHandlerException {
    ScatterMethod scatterMethod = dagNode.getScatterMethod();
    if (scatterMethod == null) {
      return new ScatterOneToOneMapping(dagNode);
    }
    switch (scatterMethod) {
    case dotproduct:
      return new ScatterOneToOneMapping(dagNode);
    case flat_crossproduct:
      return new ScatterCartesianMapping(dagNode);
    default:
      throw new EventHandlerException("Scatter method " + scatterMethod + " is not supported.");
    }
  }
  
  /**
   * Get number of scattered jobs 
   */
  private int getNumberOfScattered(JobRecord job, InputUpdateEvent event) {
    if (event.getScatteredNodes() != null) {
      return Math.max(event.getScatteredNodes(), job.getScatterMapping().getNumberOfRows());
    } else {
      return job.getScatterMapping().getNumberOfRows();
    }
  }

  /**
   * Send events from scatter wrapper to scattered jobs
   */
  private void sendValuesToScatteredJobs(JobRecord job, VariableRecord variable, InputUpdateEvent event) throws EventHandlerException {
    List<LinkRecord> links = linkService.findBySourceAndDestinationType(job.getId(), event.getPortId(), LinkPortType.INPUT, event.getContextId());

    List<Event> events = new ArrayList<>();
    for (LinkRecord link : links) {
      VariableRecord destinationVariable = variableService.find(link.getDestinationJobId(), link.getDestinationJobPort(), LinkPortType.INPUT, event.getContextId());

      Event updateInputEvent = new InputUpdateEvent(event.getContextId(), destinationVariable.getJobId(), destinationVariable.getPortId(), variable.getValue());
      events.add(updateInputEvent);
    }
    for (Event subevent : events) {
      eventProcessor.send(subevent);
    }
  }

  /**
   * Unwraps {@link DAGContainer}
   */
  private void rollOutContainer(JobRecord job, DAGContainer containerNode, String contextId) {
    for (DAGNode node : containerNode.getChildren()) {
      String newJobId = InternalSchemaHelper.concatenateIds(job.getId(), InternalSchemaHelper.getLastPart(node.getId()));

      JobRecord childJob = new JobRecord(contextId, newJobId, JobHelper.generateUniqueId(), JobState.PENDING, node instanceof DAGContainer, false, false);
      jobService.create(childJob);

      for (DAGLinkPort port : node.getInputPorts()) {
        VariableRecord childVariable = new VariableRecord(contextId, newJobId, port.getId(), LinkPortType.INPUT, null);
        variableService.create(childVariable);
      }

      for (DAGLinkPort port : node.getOutputPorts()) {
        VariableRecord childVariable = new VariableRecord(contextId, newJobId, port.getId(), LinkPortType.OUTPUT, null);
        variableService.create(childVariable);
      }
    }

    for (DAGLink link : containerNode.getLinks()) {
      String originalJobID = InternalSchemaHelper.normalizeId(job.getId());

      String sourceNodeId = originalJobID;
      String linkSourceNodeId = link.getSource().getNodeId();
      if (linkSourceNodeId.startsWith(originalJobID)) {
        if (linkSourceNodeId.equals(sourceNodeId)) {
          sourceNodeId = job.getId();
        } else {
          sourceNodeId = InternalSchemaHelper.concatenateIds(job.getId(), InternalSchemaHelper.getLastPart(linkSourceNodeId));
        }
      }
      String destinationNodeId = originalJobID;
      String linkDestinationNodeId = link.getDestination().getNodeId();
      if (linkDestinationNodeId.startsWith(originalJobID)) {
        if (linkDestinationNodeId.equals(destinationNodeId)) {
          destinationNodeId = job.getId();
        } else {
          destinationNodeId = InternalSchemaHelper.concatenateIds(job.getId(), InternalSchemaHelper.getLastPart(linkDestinationNodeId));
        }
      }

      LinkRecord childLink = new LinkRecord(contextId, sourceNodeId, link.getSource().getId(), LinkPortType.valueOf(link.getSource().getType().toString()), destinationNodeId, link.getDestination().getId(), LinkPortType.valueOf(link.getDestination().getType().toString()));
      linkService.create(childLink);

      handleLinkPort(jobService.find(sourceNodeId, contextId), link.getSource());
      handleLinkPort(jobService.find(destinationNodeId, contextId), link.getDestination());
    }
  }
  
  /**
   * Handle links for roll-out 
   */
  private void handleLinkPort(JobRecord job, DAGLinkPort linkPort) {
    if (linkPort.getType().equals(LinkPortType.INPUT)) {
      if (job.getState().equals(JobState.PENDING)) {
        job.incrementPortCounter(linkPort, LinkPortType.INPUT);
      }
    } else {
      job.incrementPortCounterIfThereIsNo(linkPort, LinkPortType.OUTPUT);
    }
    jobService.update(job);
  }

}