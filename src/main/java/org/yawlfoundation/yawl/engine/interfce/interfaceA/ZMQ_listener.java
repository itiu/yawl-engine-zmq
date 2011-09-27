package org.yawlfoundation.yawl.engine.interfce.interfaceA;

import java.io.UnsupportedEncodingException;

import org.json.simple.JSONArray;
import org.json.simple.parser.JSONParser;
import org.json.simple.parser.ParseException;
import org.yawlfoundation.yawl.engine.YSpecificationID;
import org.zeromq.ZMQ;

public class ZMQ_listener extends Thread
{
	String bindTo = "tcp://*:6665";
	private JSONParser jp;

	public ZMQ_listener()
	{
		jp = new JSONParser();
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

			try
			{
				String in_msg_str = new String(data, "UTF-8");
				JSONArray in_msg = (JSONArray) jp.parse(in_msg_str);
				selector(in_msg);

			} catch (UnsupportedEncodingException e)
			{
				// TODO Auto-generated catch block
				e.printStackTrace();
			} catch (ParseException e)
			{
				// TODO Auto-generated catch block
				e.printStackTrace();
			}

			s.send(data, 0);
		}
	}

	private void selector(JSONArray msg)
	{
		
	}

}
