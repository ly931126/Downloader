package com.zv.downloader;

import android.os.Bundle;
import android.os.Environment;
import android.support.v7.app.AppCompatActivity;
import android.widget.ListView;

import java.io.File;

/**
 * Created by ZhangWei on 2017/2/15.
 */

public abstract class DownloadActivity extends AppCompatActivity
{
	private static final String LOCAL_IP =  "http://192.168.33.72/";
	protected static final String FILMON_URL = LOCAL_IP + "Filmon.apk";
	protected static final String ROMUPDATE_URL = LOCAL_IP + "RomUpdate_V101_FW101_01_00_20170214.bin";
	protected static final String QQ_URL = "http://gdown.baidu.com/data/wisegame/dc429998555b7d4d/QQ_398.apk";
	protected static final String APPMARKET_URL = "http://63.141.241.34:8080//apks/20161031195947.apk";

	private static final String DOWNLOAD_DIR = "ZVDownloader";
	private static final String MNT_DIR = "/mnt/";
	protected static final String DOWNLOAD_PATH;

	static
	{
		File file = new File(Environment.getExternalStorageDirectory().getPath(), DOWNLOAD_DIR);
		if (file.exists() || file.mkdirs())
			DOWNLOAD_PATH = file.getPath();
		else
		{
			file = new File(MNT_DIR, DOWNLOAD_DIR);
			if (file.exists() || file.mkdirs())
				DOWNLOAD_PATH = file.getPath();
			else
				DOWNLOAD_PATH = MNT_DIR;
		}

	}

	protected ListView mDownloadListView = null;

	@Override
	protected void onCreate(Bundle savedInstanceState)
	{
		super.onCreate(savedInstanceState);
		setContentView(R.layout.activity_download);
		initDownloader();
		initView();
	}

	private void initView()
	{
		mDownloadListView = (ListView) findViewById(R.id.download_list_view);
	}

	@Override
	public void finish()
	{
		destroyDownloader();
		super.finish();
	}

	protected abstract void initDownloader();

	protected abstract void destroyDownloader();

}
