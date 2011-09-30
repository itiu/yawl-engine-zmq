package org.yawlfoundation.yawl.engine.interfce.interfaceA;

import java.io.UnsupportedEncodingException;
import java.net.ConnectException;
import java.rmi.RemoteException;
import java.util.HashMap;
import java.util.UUID;

import org.gost19.pacahon.client.PacahonClient;
import org.json.simple.JSONObject;
import org.json.simple.parser.JSONParser;
import org.json.simple.parser.ParseException;
import org.yawlfoundation.yawl.authentication.YClient;
import org.yawlfoundation.yawl.authentication.YSession;
import org.yawlfoundation.yawl.authentication.YSessionCache;
import org.yawlfoundation.yawl.engine.YEngine;
import org.yawlfoundation.yawl.engine.YWorkItem;
import org.zeromq.ZMQ;

public class ZMQ_listener extends Thread
{
	String bindTo = "tcp://*:6665";
	private JSONParser jp;
	private YEngine _engine;

	private static HashMap<String, PacahonClient> uri_pacahonClient;
	private static HashMap<String, String> uri_ticket;

	public ZMQ_listener(YEngine in_engine)
	{
		jp = new JSONParser();
		_engine = in_engine;
		_sessionCache = _engine.getSessionCache();

		if (uri_pacahonClient == null)
			uri_pacahonClient = new HashMap<String, PacahonClient>();

		if (uri_ticket == null)
			uri_ticket = new HashMap<String, String>();
	}

	public void run()
	{
		System.out.println("start zmq listener: " + bindTo);
		ZMQ.Context ctx = ZMQ.context(1);
		ZMQ.Socket s = ctx.socket(ZMQ.REP);

		//  Add your socket options here.
		//  For example ZMQ_RATE, ZMQ_RECOVERY_IVL and ZMQ_MCAST_LOOP for PGM.

		s.bind(bindTo);

		while (true)
		{
			String res = "";

			try
			{
				byte[] data = s.recv(0);

				try
				{
					String in_msg_str = new String(data, "UTF-8");
					System.out.println("recieve from zmq: " + in_msg_str);
					JSONObject in_msg = (JSONObject) jp.parse(in_msg_str);
					res = selector(in_msg);

				} catch (UnsupportedEncodingException e)
				{
					// TODO Auto-generated catch block
					e.printStackTrace();
				} catch (ParseException e)
				{
					// TODO Auto-generated catch block
					e.printStackTrace();
				} catch (RemoteException e)
				{
					// TODO Auto-generated catch block
					e.printStackTrace();
				}

				if (res == null)
					res = "";

				s.send(res.getBytes(), 0);
			} catch (org.zeromq.ZMQException e)
			{
				if (e.getErrorCode() == 156384763)
				{
					s.send("".getBytes(), 0);
				}
			} catch (Exception e)
			{
				// TODO Auto-generated catch block
				e.printStackTrace();
			}

		}
	}

	private String selector(JSONObject msg) throws Exception
	{
		String out_msg = null;

		String from = "yawl-engine:ZMQ_listener";

		if (msg == null)
			return null;

		String msg_id = (String) msg.get("@");
		String target = (String) msg.get("msg:reciever");

		if (target != null && target.equals("yawl-engine"))
		{
			String command = (String) msg.get("msg:command");

			if (command != null)
			{
				JSONObject args = (JSONObject) msg.get("msg:args");
				if (command.equals("get_ticket"))
				{
					String login = (String) args.get("auth:login");
					String credential = (String) args.get("auth:credential");

					int interval = 1 * 60 * 60;
					String ticket = connect(login, credential, interval);

					UUID msg_uuid = UUID.randomUUID();

					out_msg = "{\n \"@\" : \"msg:M"
							+ msg_uuid
							+ "\", \n \"a\" : \"msg:Message\",\n"
							+ "\"msg:in-reply-to\" : \""
							+ msg_id
							+ "\",\n"
							+ "\"msg:sender\" : \""
							+ from
							+ "\",\n"
							+ "\"msg:reciever\" : \"pacahon\",\n \"msg:status\" : \"ok\",\n \"msg:result\" : \n{\n \"auth:ticket\" : \""
							+ ticket + "\"\n}\n}";

				} else if (command.equals("checkout"))
				{
					String ticket = (String) args.get("auth:ticket");
					String taskId = (String) args.get("yawl:taskId");
					YWorkItem res_ywi = _engine.startWorkItem(taskId, getClient(ticket));

					if (res_ywi != null)
					{
						UUID msg_uuid = UUID.randomUUID();

						out_msg = "{\n \"@\" : \"msg:M" + msg_uuid + "\", \n \"a\" : \"msg:Message\",\n"
								+ "\"msg:in-reply-to\" : \"" + msg_id + "\",\n" + "\"msg:sender\" : \"" + from
								+ "\",\n"
								+ "\"msg:reciever\" : \"pacahon\",\n \"msg:status\" : \"ok\",\n \"msg:result\" : \n"
								+ res_ywi.toJsonObject() + "\n}";

					}

				} else if (command.equals("checkin"))
				{
					String ticket = (String) args.get("auth:ticket");
					String taskId = (String) args.get("yawl:taskId");
					String caseId = (String) args.get("yawl:caseId");
					JSONObject data = (JSONObject) args.get("yawl:data");
					String reason = (String) args.get("yawl:reason");

					boolean force = false;

					YWorkItem workItem = _engine.getWorkItem(caseId + ":" + taskId);
					if (workItem != null)
					{

						YEngine.WorkItemCompletion flag = force ? YEngine.WorkItemCompletion.Force
								: YEngine.WorkItemCompletion.Normal;

						_engine.completeWorkItem(workItem, data.toString(), reason, flag);

						UUID msg_uuid = UUID.randomUUID();

						out_msg = "{\n \"@\" : \"msg:M" + msg_uuid + "\", \n \"a\" : \"msg:Message\",\n"
								+ "\"msg:in-reply-to\" : \"" + msg_id + "\",\n" + "\"msg:sender\" : \"" + from
								+ "\",\n"
								+ "\"msg:reciever\" : \"pacahon\",\n \"msg:status\" : \"ok\",\n \"msg:result\" : "
								+ "\"success\"" + "\n}";

					}

				}
			}
		}

		return out_msg;
	}

	private YSessionCache _sessionCache;

	public String connect(String userID, String password, long timeOutSeconds) throws RemoteException
	{
		if (userID.equals("admin") && (!_engine.isGenericAdminAllowed()))
		{
			return "The generic 'admin' user has been disabled.";
		}
		return _sessionCache.connect(userID, password, timeOutSeconds);
	}

	private YClient getClient(String sessionHandle)
	{
		YSession session = _sessionCache.getSession(sessionHandle);
		return (session != null) ? session.getClient() : null;
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
