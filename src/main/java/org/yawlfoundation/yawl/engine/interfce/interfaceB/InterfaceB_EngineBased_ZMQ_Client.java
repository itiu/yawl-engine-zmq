/*
 * Copyright (c) 2004-2011 The YAWL Foundation. All rights reserved.
 * The YAWL Foundation is a collaboration of individuals and
 * organisations who are committed to improving workflow technology.
 *
 * This file is part of YAWL. YAWL is free software: you can
 * redistribute it and/or modify it under the terms of the GNU Lesser
 * General Public License as published by the Free Software Foundation.
 *
 * YAWL is distributed in the hope that it will be useful, but WITHOUT
 * ANY WARRANTY; without even the implied warranty of MERCHANTABILITY
 * or FITNESS FOR A PARTICULAR PURPOSE. See the GNU Lesser General
 * Public License for more details.
 *
 * You should have received a copy of the GNU Lesser General Public
 * License along with YAWL. If not, see <http://www.gnu.org/licenses/>.
 */

package org.yawlfoundation.yawl.engine.interfce.interfaceB;

import static org.yawlfoundation.yawl.engine.announcement.YEngineEvent.CASE_CANCELLED;
import static org.yawlfoundation.yawl.engine.announcement.YEngineEvent.CASE_COMPLETE;
import static org.yawlfoundation.yawl.engine.announcement.YEngineEvent.CASE_RESUMED;
import static org.yawlfoundation.yawl.engine.announcement.YEngineEvent.CASE_SUSPENDED;
import static org.yawlfoundation.yawl.engine.announcement.YEngineEvent.CASE_SUSPENDING;
import static org.yawlfoundation.yawl.engine.announcement.YEngineEvent.ENGINE_INIT;
import static org.yawlfoundation.yawl.engine.announcement.YEngineEvent.ITEM_ADD;
import static org.yawlfoundation.yawl.engine.announcement.YEngineEvent.ITEM_CANCEL;
import static org.yawlfoundation.yawl.engine.announcement.YEngineEvent.ITEM_STATUS;
import static org.yawlfoundation.yawl.engine.announcement.YEngineEvent.TIMER_EXPIRED;

import java.io.IOException;
import java.net.ConnectException;
import java.text.MessageFormat;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Hashtable;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import org.apache.log4j.Logger;
import org.gost19.pacahon.client.PacahonClient;
import org.gost19.pacahon.client.Predicates;
import org.jdom.Document;
import org.jdom.Element;
import org.jdom.JDOMException;
import org.json.simple.JSONArray;
import org.json.simple.JSONObject;
import org.yawlfoundation.yawl.elements.YAWLServiceReference;
import org.yawlfoundation.yawl.elements.data.YParameter;
import org.yawlfoundation.yawl.elements.state.YIdentifier;
import org.yawlfoundation.yawl.engine.ObserverGateway;
import org.yawlfoundation.yawl.engine.YEngine;
import org.yawlfoundation.yawl.engine.YWorkItem;
import org.yawlfoundation.yawl.engine.YWorkItemStatus;
import org.yawlfoundation.yawl.engine.announcement.YAnnouncement;
import org.yawlfoundation.yawl.engine.announcement.YEngineEvent;
import org.yawlfoundation.yawl.unmarshal.YDecompositionParser;
import org.yawlfoundation.yawl.util.HttpURLValidator;
import org.yawlfoundation.yawl.util.JDOMUtil;

public class InterfaceB_EngineBased_ZMQ_Client implements ObserverGateway
{

	protected static final Logger _logger = Logger.getLogger(InterfaceB_EngineBased_ZMQ_Client.class);
	private static final ExecutorService _executor = Executors.newFixedThreadPool(Runtime.getRuntime()
			.availableProcessors());

	private static HashMap<String, PacahonClient> uri_pacahonClient;
	private static HashMap<String, String> uri_ticket;

	public InterfaceB_EngineBased_ZMQ_Client()
	{
		if (uri_pacahonClient == null)
			uri_pacahonClient = new HashMap<String, PacahonClient>();

		if (uri_ticket == null)
			uri_ticket = new HashMap<String, String>();
	}

	/**
	 * Indicates which protocol this shim services.
	 * 
	 * @return the scheme
	 */
	public String getScheme()
	{
		return "tcp";
	}

	/**
	 * PRE: The work item is enabled. announces a work item to a YAWL Service.
	 * 
	 * @param announcement
	 */
	public void announceFiredWorkItem(YAnnouncement announcement)
	{
		_executor.execute(new Handler(announcement.getYawlService(), announcement.getItem(), ITEM_ADD));
	}

	public void announceCancelledWorkItem(YAnnouncement announcement)
	{
		YAWLServiceReference yawlService = announcement.getYawlService();
		YWorkItem workItem = announcement.getItem();
		if (workItem.getParent() != null)
		{
			YWorkItem parent = workItem.getParent();
			cancelWorkItem(yawlService, parent);
			Set<YWorkItem> children = parent.getChildren();
			if (children != null)
			{
				for (YWorkItem item : children)
				{
					cancelWorkItem(yawlService, item);
				}
			}
		} else
			cancelWorkItem(yawlService, workItem);
	}

	/**
	 * Announces work item cancellation to the YAWL Service.
	 * 
	 * @param yawlService
	 *            the YAWL service reference.
	 * @param workItem
	 *            the work item to cancel.
	 */
	public void cancelWorkItem(YAWLServiceReference yawlService, YWorkItem workItem)
	{
		_executor.execute(new Handler(yawlService, workItem, ITEM_CANCEL));
	}

	/**
	 * Announces a workitem timer expiry
	 * 
	 * @param announcement
	 *            the yawl service reference.
	 */
	public void announceTimerExpiry(YAnnouncement announcement)
	{
		YAWLServiceReference yawlService = announcement.getYawlService();
		YWorkItem workItem = announcement.getItem();
		_executor.execute(new Handler(yawlService, workItem, TIMER_EXPIRED));
	}

	/**
	 * Called by the engine to announce when a case suspends (i.e. becomes fully
	 * suspended) as opposed to entering the 'suspending' state.
	 */
	public void announceCaseSuspended(Set<YAWLServiceReference> services, YIdentifier caseID)
	{
		for (YAWLServiceReference service : services)
		{
			_executor.execute(new Handler(service, caseID, CASE_SUSPENDED));
		}
	}

	/**
	 * Called by the engine to announce when a case starts to suspends (i.e.
	 * enters the suspending state) as opposed to entering the fully 'suspended'
	 * state.
	 */
	public void announceCaseSuspending(Set<YAWLServiceReference> services, YIdentifier caseID)
	{
		for (YAWLServiceReference service : services)
		{
			_executor.execute(new Handler(service, caseID, CASE_SUSPENDING));
		}
	}

	/**
	 * Called by the engine to announce when a case resumes from a previous
	 * 'suspending' or 'suspended' state.
	 */
	public void announceCaseResumption(Set<YAWLServiceReference> services, YIdentifier caseID)
	{
		for (YAWLServiceReference service : services)
		{
			_executor.execute(new Handler(service, caseID, CASE_RESUMED));
		}
	}

	/**
	 * Notify of a change of status for a work item.
	 * 
	 * @param workItem
	 *            that has changed
	 * @param oldStatus
	 *            previous status
	 * @param newStatus
	 *            new status
	 */
	public void announceWorkItemStatusChange(Set<YAWLServiceReference> services, YWorkItem workItem,
			YWorkItemStatus oldStatus, YWorkItemStatus newStatus)
	{
		for (YAWLServiceReference service : services)
		{
			_executor.execute(new Handler(service, workItem, oldStatus.toString(), newStatus.toString(), ITEM_STATUS));
		}
	}

	/**
	 * Called by engine to announce when a case is complete.
	 * 
	 * @param caseID
	 *            the case that completed
	 * @param caseData
	 *            the output data of the case
	 */
	public void announceCaseCompletion(Set<YAWLServiceReference> services, YIdentifier caseID, Document caseData)
	{
		for (YAWLServiceReference service : services)
		{
			announceCaseCompletion(service, caseID, caseData);
		}
	}

	/**
	 * Called by engine to announce when a case is complete.
	 * 
	 * @param yawlService
	 *            the yawl service
	 * @param caseID
	 *            the case that completed
	 */
	public void announceCaseCompletion(YAWLServiceReference yawlService, YIdentifier caseID, Document caseData)
	{
		_executor.execute(new Handler(yawlService, caseID, caseData, CASE_COMPLETE));
	}

	/**
	 * Called by the engine when it has completed initialisation and is running
	 * 
	 * @param services
	 *            a set of custom services to receive the announcement
	 * @param maxWaitSeconds
	 *            the maximum seconds to wait for services to be contactable
	 */
	public void announceEngineInitialised(Set<YAWLServiceReference> services, int maxWaitSeconds)
	{
		for (YAWLServiceReference service : services)
		{
			_executor.execute(new Handler(service, maxWaitSeconds, ENGINE_INIT));
		}
	}

	/**
	 * Called by the engine to announce the cancellation of a case
	 * 
	 * @param services
	 *            a set of custom services to receive the announcement
	 * @param id
	 *            the case id of the cancelled case
	 */
	public void announceCaseCancellation(Set<YAWLServiceReference> services, YIdentifier id)
	{
		for (YAWLServiceReference service : services)
		{
			_executor.execute(new Handler(service, id, CASE_CANCELLED));
		}
	}

	/**
	 * Called by the engine to announce shutdown of the engine's servlet
	 * container
	 */
	public void shutdown()
	{
		_executor.shutdownNow();

		// Nothing else to do - Interface B Clients handle shutdown within their own servlet.
	}

	/**
	 * Returns an array of YParameter objects that describe the YAWL service
	 * being referenced.
	 * 
	 * @param yawlService
	 *            the YAWL service reference.
	 * @return an array of YParameter objects.
	 * @throws IOException
	 *             if connection problem
	 * @throws JDOMException
	 *             if XML content problem.
	 */
	public YParameter[] getRequiredParamsForService(YAWLServiceReference yawlService) throws Exception, IOException,
			JDOMException
	{
		List<YParameter> paramResults = new ArrayList<YParameter>();
		Map<String, String> paramMap = new Hashtable<String, String>();
		JSONArray vars = executeGet("ParameterInfoRequest", yawlService.getURI(), paramMap);

		Iterator<JSONObject> subj_it = vars.iterator();
		while (subj_it.hasNext())
		{
			JSONObject ss = subj_it.next();

			String name = (String) ss.get("@");
			String type = (String) ss.get("rdf:type");
			String datatype = (String) ss.get("process:parameterType");
			YParameter param = null;

			if (type.equals("process:Input"))
				param = new YParameter(null, "inputParam");
			else if ((type.equals("process:Output")))
				param = new YParameter(null, "outputParam");

			if (param != null)
			{
				//			YDecompositionParser.parseParameter(paramElem, param, null, false);
				param.setDataTypeAndName(datatype.replaceAll("xsd:", ""), name, null);
				paramResults.add(param);
			}

		}

		return paramResults.toArray(new YParameter[paramResults.size()]);
	}

	/*******************************************************************************/
	/*******************************************************************************/

	/*
	 * This internal class sends the specified announcement and any required
	 * parameter values as send messages to external custom services
	 */

	private class Handler implements Runnable
	{
		private YWorkItem _workItem;
		private YAWLServiceReference _yawlService;
		private YEngineEvent _command;
		private YIdentifier _caseID;
		private Document _caseData;
		private String _oldStatus;
		private String _newStatus;
		private int _pingTimeout = 5;

		public Handler(YAWLServiceReference yawlService, YWorkItem workItem, YEngineEvent command)
		{
			_workItem = workItem;
			_yawlService = yawlService;
			_command = command;
		}

		public Handler(YAWLServiceReference yawlService, YWorkItem workItem, String oldStatus, String newStatus,
				YEngineEvent command)
		{
			_workItem = workItem;
			_yawlService = yawlService;
			_command = command;
			_oldStatus = oldStatus;
			_newStatus = newStatus;
		}

		public Handler(YAWLServiceReference yawlService, YIdentifier caseID, Document casedata, YEngineEvent command)
		{
			_yawlService = yawlService;
			_caseID = caseID;
			_command = command;
			_caseData = casedata;
		}

		public Handler(YAWLServiceReference yawlService, int pingTimeout, YEngineEvent command)
		{
			_yawlService = yawlService;
			_pingTimeout = pingTimeout;
			_command = command;
		}

		public Handler(YAWLServiceReference yawlService, YIdentifier id, YEngineEvent command)
		{
			_yawlService = yawlService;
			_caseID = id;
			_command = command;
		}

		/**
		 * Load parameter map as required, then POST the message to the custom
		 * service
		 */
		public void run()
		{
			Map<String, String> paramsMap = prepareParamMap(_command.label(), null);
			//			if (_workItem != null)
			//				paramsMap.put("workItem", _workItem.toXML());
			if (_caseID != null)
				paramsMap.put("caseID", _caseID.toString());
			try
			{
				switch (_command)
				{
				case ITEM_STATUS:
				{
					paramsMap.put("oldStatus", _oldStatus);
					paramsMap.put("newStatus", _newStatus);
					break;
				}
				case CASE_COMPLETE:
				{
					paramsMap.put("casedata", JDOMUtil.documentToString(_caseData));
					break;
				}
				case ENGINE_INIT:
				{
					HttpURLValidator.pingUntilAvailable(_yawlService.getURI(), _pingTimeout);
					break;
				}
				}
				String action = paramsMap.get("action");
				executePost(action, _yawlService.getURI(), paramsMap, _workItem);
			} catch (ConnectException ce)
			{
				if (_command == ITEM_ADD)
				{
					redirectWorkItem(true);
				} else if (_command == ENGINE_INIT)
				{
					_logger.warn(MessageFormat.format("Failed to announce engine initialisation to {0} at URI [{1}]",
							_yawlService.getServiceName(), _yawlService.getURI()));
				}
			} catch (IOException e)
			{

				if (_command == ITEM_ADD)
				{
					redirectWorkItem(false);
				}

				// ignore broadcast announcements for missing services
				else if (!_command.isBroadcast())
				{
					_logger.warn("Failed to call YAWL service", e);
				}
			}
		}

		private void redirectWorkItem(boolean connect)
		{
			YAWLServiceReference defWorklist = YEngine.getInstance().getDefaultWorklist();
			if (defWorklist == null)
			{
				_logger.error(MessageFormat.format(
						"Could not {0} YAWL Service at URL [{1}] to announce enabled workitem"
								+ " [{2}], and cannot redirect workitem to default worklist handler"
								+ " because there is no default handler known to the engine.", connect ? "connect to"
								: "find", _yawlService.getURI(), _workItem.getIDString()));
			} else if (!defWorklist.getURI().equals(_yawlService.getURI()))
			{
				_logger.warn(MessageFormat.format(
						"Could not {0} YAWL Service at URL [{1}] to announce enabled workitem"
								+ " [{2}]. Redirecting workitem to default worklist handler.", connect ? "connect to"
								: "find", _yawlService.getURI(), _workItem.getIDString()));
				YEngine.getInstance().getAnnouncer().rejectAnnouncedEnabledTask(_workItem);
			} else
			{
				_logger.error(MessageFormat.format(
						"Could not announce enabled workitem [{0}] to default worklist "
								+ "handler at URL [{1}]. Either the handler is missing or offline, "
								+ "or the URL is invalid.", _workItem.getIDString(), _yawlService.getURI()));
			}
		}
	}

	/////////////////////////////////////////////////////////////////////////
	private JSONArray executeGet(String action, String urlStr, Map<String, String> paramsMap) throws ConnectException,
			IOException
	{
		JSONArray result = null;

		try
		{
			PacahonClient pclient = getPacahonClient(urlStr);

			JSONObject arg = new JSONObject();

			if (action.equals("ParameterInfoRequest"))
			{
				result = pclient.send_command(uri_ticket.get(urlStr), "yawl:ParameterInfoRequest", arg,
						"InterfaceB_EngineBased_ZMQ_Client: executePost");
			}
		} catch (Exception ex)
		{
			throw new IOException(ex);
		}

		return result;

	}

	private Map<String, String> prepareParamMap(String action, String handle)
	{
		Map<String, String> paramMap = new HashMap<String, String>();
		paramMap.put("action", action);
		if (handle != null)
			paramMap.put("sessionHandle", handle);
		return paramMap;
	}

	private String executePost(String action, String urlStr, Map<String, String> paramsMap, YWorkItem _workItem)
			throws IOException
	{
		try
		{
			PacahonClient pclient = getPacahonClient(urlStr);

			JSONObject arg = new JSONObject();

			if (action.equals("announceItemEnabled") && _workItem != null)
			{
				arg.put("@", "zdb:" + _workItem.getTaskID() + "-" + _workItem.getCaseID());
				arg.put("a", "yawl:workItem");

				arg.put("yawl:taskId", _workItem.getTaskID());
				arg.put("yawl:caseId", _workItem.getCaseID());

				if (_workItem.getTask().getName() != null)
					arg.put("yawl:taskName", _workItem.getTask().getName());

				if (_workItem.get_specIdentifier() != null)
					arg.put("yawl:specIdentifier", _workItem.get_specIdentifier());

				if (_workItem.get_specVersion() != null)
					arg.put("yawl:specVersion", _workItem.get_specVersion());

				if (_workItem.get_specUri() != null)
					arg.put("yawl:specUri", _workItem.get_specUri());

				if (_workItem.get_status() != null)
					arg.put("yawl:status", _workItem.get_status());

				if (_workItem.get_enablementTime() != null)
				{
					arg.put("yawl:enablementTime", _workItem.get_enablementTime() + "");
					arg.put("yawl:enablementTimeMs", _workItem.get_enablementTime().getTime() + "");
				}

				JSONArray result = pclient.send_command(uri_ticket.get(urlStr), "yawl:announceItemEnabled", arg,
						"InterfaceB_EngineBased_ZMQ_Client: executePost");

			}
		} catch (Exception ex)
		{
			throw new IOException(ex);
		}

		return null;
	}

	private PacahonClient getPacahonClient(String urlStr) throws ConnectException
	{
		try
		{
			PacahonClient pclient = uri_pacahonClient.get(urlStr);

			if (pclient == null)
			{
				pclient = new PacahonClient(urlStr);
				uri_pacahonClient.put(urlStr, pclient);
				String ticket = pclient
						.get_ticket("user", "9cXsvbvu8=", "InterfaceB_EngineBased_ZMQ_Client:executeGet");
				uri_ticket.put(urlStr, ticket);
			}

			return pclient;
		} catch (Exception ex)
		{
			throw new ConnectException("getPacahonClient:" + ex.getMessage());
		}
	}

}
