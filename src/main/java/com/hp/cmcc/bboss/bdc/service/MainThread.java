package com.hp.cmcc.bboss.bdc.service;

import java.io.UnsupportedEncodingException;
import java.util.Comparator;
import java.util.Iterator;
import java.util.LinkedList;
import java.util.List;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import com.hp.cmcc.bboss.bdc.handle.HandleThread;
import com.hp.cmcc.bboss.bdc.pojo.BbdcTypeCdr;
import com.hp.cmcc.bboss.bdc.pojo.compare.BdcComRecordCheckResult;
import com.hp.cmcc.bboss.bdc.pojo.compare.BdcCompareResult;
import com.hp.cmcc.bboss.bdc.utils.BaseUtil;
import com.hp.cmcc.bboss.bdc.utils.PubData;
import com.hp.cmcc.bboss.bdc.utils.Tools;

@Service
public class MainThread {
	private static Logger L = LoggerFactory.getLogger(MainThread.class);
	private static BaseUtil baseUtil = new BaseUtil();

	public BdcCompareResult handle(List<String> fileBody, List<BbdcTypeCdr> rule, String fileName, String tranId) {
		BdcCompareResult bcr = new BdcCompareResult();
	    List<String> doneRecs = new LinkedList<String>();
	    List<String> errRecs = new LinkedList<String>();
	    List<String> errRecReport = new LinkedList<String>();
	    List<String> taskSql = new LinkedList<String>();
	    List<BbdcTypeCdr> doneList = new LinkedList<BbdcTypeCdr>();
		List<BbdcTypeCdr> errList = new LinkedList<BbdcTypeCdr>();
		String errInfoTableName = "";//错误信息可以在该表中查到此记录；
		int checkNum = 0;
		rule.sort(new Comparator<BbdcTypeCdr>() {
			@Override
			public int compare(BbdcTypeCdr o1, BbdcTypeCdr o2) {
				return o1.getHinderIdx().intValue()-o2.getHinderIdx().intValue();
			}
		});
		
		for(BbdcTypeCdr cdr : rule) {
			if(!"CONTERROR".equals(cdr.getValName().trim())) {
				if("ERR_INFO_TABLE_NAME".equals(cdr.getFieldName())) {
					errInfoTableName = cdr.getDataFiller();
				}else {
					if(cdr.getFormerIdx() != -1) {
						checkNum++;
					}
					doneList.add(cdr);
				}
			}else {
				errList.add(cdr);
			}
		}
		
		int corePoolSize = Runtime.getRuntime().availableProcessors();
	    ExecutorService executor  = Executors.newFixedThreadPool(corePoolSize);
	    List<Future<BdcComRecordCheckResult>> resultList = new LinkedList<Future<BdcComRecordCheckResult>>();
	    int size = fileBody.size();
	    long lineNum = 1;
	    for(int start = 0;start < size;start += corePoolSize) {
	    	int i = 0;
	    	while(start+i < size && i < corePoolSize){
	    		String record = fileBody.get(start+i);
	    		if(Tools.IsBlank(record)) {
	    			lineNum++;
	    			i++;
	    			continue;
	    		}
	    		HandleThread handleThread = new HandleThread(record, doneList, fileName, lineNum++, tranId, checkNum, errInfoTableName);
	    		resultList.add(executor.submit(handleThread));
	    		i++;
	    	}
	    }
		
	    Iterator<Future<BdcComRecordCheckResult>> iterator = resultList.iterator();
	    try {
			while(iterator.hasNext()) {
				BdcComRecordCheckResult bdcComRecordCheckResult = iterator.next().get();
				if(Tools.IsEmpty(bdcComRecordCheckResult.getErrStr())) {
					doneRecs.add(bdcComRecordCheckResult.getDoneStr());
				}else {
					errRecs.add(bdcComRecordCheckResult.getErrStr());
					errRecReport.add(bdcComRecordCheckResult.getErrRptStr());
				}
			}
	    }catch (InterruptedException | ExecutionException  e) {
			L.error("threads exception!",e);
		}
	    //向db2f表中插入一天任务
	    String taskStr = "";
	    try {
		    if(errRecs.size() == 0) {
		    	errRecReport.add("null,null,null,null,null,null,null,'"+tranId+"'");
		    	taskStr = new String(baseUtil.getTaskSql(PubData.TASD_DONE_SQL	, "\\?", tranId,Tools.getNowTime(PubData.TMFMT6)).getBytes("GBK"),"GBK");
		    }else {
		    	taskStr = new String(baseUtil.getTaskSql(PubData.TASD_SQL	, "\\?", tranId,Tools.getNowTime(PubData.TMFMT6)).getBytes("GBK"),"GBK");
		    }
	    } catch (UnsupportedEncodingException e) {
	    	L.error("encode switch error!",e);
	    }
	    taskSql.add(taskStr);
	    
	    bcr.setDoneRec(doneRecs);
	    bcr.setErrRec(errRecs);
	    bcr.setErrRecReport(errRecReport);
	    bcr.setTaskSql(taskSql);
	    
		return bcr;
	}

}
