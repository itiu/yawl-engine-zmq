package org.yawlfoundation.yawl.engine.interfce.interfaceA;

import java.io.UnsupportedEncodingException;
import java.rmi.RemoteException;
import java.util.UUID;

import org.json.simple.JSONArray;
import org.json.simple.JSONObject;
import org.json.simple.parser.JSONParser;
import org.json.simple.parser.ParseException;
import org.yawlfoundation.yawl.engine.interfce.EngineGateway;
import org.zeromq.ZMQ;

public class ZMQ_listener extends Thread
{
	String bindTo = "tcp://*:6665";
	private JSONParser jp;
	private EngineGateway _engine;

	public ZMQ_listener(EngineGateway in_engine)
	{
		jp = new JSONParser();
		_engine = in_engine;
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
			byte[] data = s.recv(0);
			String res = "";

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

			s.send(res.getBytes(), 0);
		}
	}

	private String selector(JSONObject msg) throws RemoteException
	{
		String res = null;

		String from = "yawl-engine:ZMQ_listener";

		if (msg == null)
			return null;

		String msg_id = (String) msg.get("@");
		String target = (String) msg.get("msg:reciever");

		if (target != null && target.equals("yawl-engine"))
		{
			String command = (String) msg.get("msg:command");

			if (command != null && command.equals("get_ticket"))
			{
				JSONObject args = (JSONObject) msg.get("msg:args");
				String login = (String) args.get("auth:login");
				String credential = (String) args.get("auth:credential");

				int interval = 1 * 60 * 60;
				String ticket = _engine.connect(login, credential, interval);

				UUID msg_uuid = UUID.randomUUID();

				String out_msg = "{\n \"@\" : \"msg:M"
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

				res = out_msg;
			}

		}

		return res;
	}

}
