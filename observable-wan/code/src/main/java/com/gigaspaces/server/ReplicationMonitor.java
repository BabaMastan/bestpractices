package com.gigaspaces.server;

import java.util.Timer;
import java.util.TimerTask;
import java.util.logging.Logger;

import org.openspaces.core.GigaSpace;
import org.springframework.beans.factory.annotation.Required;
import org.springframework.transaction.annotation.Transactional;

import com.gigaspaces.server.ReplicationStatus.Status;
import com.j_spaces.core.client.SQLQuery;

/**
 * The purpose of this class is to monitor the latencies of all
 * WAN connections, updating a singleton (ReplicationStatus) in the space that can
 * serve to trigger other activities via events.
 * 
 * @author dfilppi
 *
 */
public class ReplicationMonitor extends TimerTask{
	private final static Logger log=Logger.getLogger(ReplicationMonitor.class.getName());
	private GigaSpace space=null;
	private Integer timestampRate=null;
	private Long degradedLatencyThreshold=0L;
	private Long downLatencyThreshold=0L;
	private String location=null;
	private SQLQuery<TimeRecord> query=new SQLQuery<TimeRecord>(TimeRecord.class,"location <> ?");
	private Timer timer=new Timer();

	//init-method
	public void init(){
		//startup
		timer.scheduleAtFixedRate(this,0,1000L);
	}
	
	//destroy-method
	public void stop(){
		if(timer!=null)timer.cancel();
	}

	public void run() {
		query.setParameters(getLocation());
		TimeRecord[] recs=space.readMultiple(query);

		if(recs==null || recs.length==0){
			log.warning("no TimeRecords found");
			updateStatus(Status.DOWN);
			return;
		}

		/*
		 * If any site is experiencing latency issues, raise the flag
		 */
		for(TimeRecord rec:recs){
			if(rec.getSampleCount()<10){
				log.info("not enough samples:"+rec.getSampleCount()+", setting to DOWN");
				updateStatus(Status.DOWN);
				return;
			}
			double lat=(long)(Math.round(rec.getAveLatency()));
			//lat represented average latency of samples
			//handle case where last heartbeat never arrived
			Long lasttime=rec.getTime();
			if(lasttime==null){
				log.info("no timestamps recorded");
				continue;
			}
			StringBuilder sb=new StringBuilder("timelog={");
			for(int i=0;i<rec.getTimelog().size();i++){
				sb.append(rec.getTimelog().get(i).getLatency()).append(",");
			}
			//estimate possible in-flight latency
			long incomingLatency=System.currentTimeMillis()-rec.getTime()-timestampRate;
			if(incomingLatency>0 && incomingLatency < downLatencyThreshold/2){
				lat=lat+(incomingLatency*2/rec.getSampleCount());
			}
			else if(incomingLatency>0 && incomingLatency >= downLatencyThreshold/2){
				lat=downLatencyThreshold+1;  //give up
			}
			/**
			 * An enhancement for production might be to measure all latencies and record
			 * them in the replication status object for monitoring/reporting
			 */
			
			if(lat > this.downLatencyThreshold){
				log.info("exceeded down latency for location "+rec.getLocation());
				log.info("calculated latency="+lat+" status:DOWN");
				updateStatus(Status.DOWN);
				return;
			}
			if(lat > this.degradedLatencyThreshold){
				log.info("exceeded down latency for location "+rec.getLocation());
				log.info("calculated latency="+lat);
				log.info("calculated latency="+lat+" status:DEGRADED");
				updateStatus(Status.DEGRADED);
				return;
			}
		}
		//fell through, all must be good
		log.info("updating status UP");
		updateStatus(Status.UP);
	}
	

	@Transactional
	private void updateStatus(Status status) {
		ReplicationStatus rs=space.readById(ReplicationStatus.class,0,1,1000L);
		if(rs==null){
			//create it
			rs=new ReplicationStatus();
		}
		rs.setStatus(status);
		space.write(rs);
	}

	@Required
	public void setSpace(GigaSpace space) {
		this.space = space;
	}

	public GigaSpace getSpace() {
		return space;
	}

	@Required
	public void setTimestampRate(Integer timestampRate) {
		this.timestampRate = timestampRate;
	}

	public Integer getTimestampRate() {
		return timestampRate;
	}

	@Required
	public void setDownLatencyThreshold(Long downLatencyThreshold) {
		this.downLatencyThreshold = downLatencyThreshold;
	}

	public Long getDownLatencyThreshold() {
		return downLatencyThreshold;
	}

	@Required
	public void setDegradedLatencyThreshold(Long degradedLatencyThreshold) {
		this.degradedLatencyThreshold = degradedLatencyThreshold;
	}

	public Long getDegradedLatencyThreshold() {
		return degradedLatencyThreshold;
	}

	@Required
	public void setLocation(String location) {
		this.location = location;
	}

	public String getLocation() {
		return location;
	}
}
