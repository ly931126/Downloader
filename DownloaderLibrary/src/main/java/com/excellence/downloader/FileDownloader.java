package com.excellence.downloader;

/**
 * Created by MK on 2016/10/27.
 */

import java.io.File;
import java.io.RandomAccessFile;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.concurrent.Executor;

import android.content.Context;

import com.excellence.downloader.exception.DownloadError;
import com.excellence.downloader.exception.ServerConnectException;
import com.excellence.downloader.exception.SpaceNotEnoughException;
import com.excellence.downloader.exception.URLInvalidException;
import com.excellence.downloader.utils.DownloaderListener;
import com.excellence.downloader.utils.HttpUtils;
import com.excellence.downloader.utils.IDownloaderListener;
import com.excellence.downloader.utils.MemorySpaceCheck;

/***
 * 文件下载器
 */
public class FileDownloader implements IDownloaderListener
{
	private static final String TAG = FileDownloader.class.getSimpleName();

	private static final int THREAD_COUNT = 3;
	protected static final int CONNECT_TIME_OUT = 10 * 1000;
	protected static final int SO_TIME_OUT = 10 * 1000;

	public static final int STATE_DOWNLOADING = 0;
	public static final int STATE_PAUSE = 1;
	public static final int STATE_SUCCESS = 2;
	public static final int STATE_DISCARD = 3;
	public static final int STATE_ERROR = 4;

	private Context mContext;
	private File mStoreFile = null;
	private String mFileUrl;
	private String mFileName;
	private DownloaderListener mDownloaderListener = null;
	private Executor mResponsePoster = null;
	private DBHelper mDBHelper = null;
	private DownloadThread[] mDownloadThreads = new DownloadThread[THREAD_COUNT];
	private boolean isStop = false;
	private boolean isFinished = false;
	private long mDownloadSize = 0;
	private long mFileSize = 0;
	private int mState;

	public FileDownloader(Context context, File storeFile, String url, DownloaderListener listener, Executor executor)
	{
		mContext = context;
		mStoreFile = storeFile;
		mFileUrl = url;
		mFileName = storeFile.getName();
		mDownloaderListener = listener;
		mResponsePoster = executor;
		mDBHelper = DBHelper.getInstance(context);
	}

	/**
	 * 开始下载任务
	 */
	protected void deploy()
	{
		mState = STATE_DOWNLOADING;
		try
		{
			if (!mFileUrl.isEmpty())
			{
				URL url = new URL(mFileUrl);
				HttpURLConnection connection = (HttpURLConnection) url.openConnection();
				connection.setConnectTimeout(CONNECT_TIME_OUT);
				connection.setReadTimeout(SO_TIME_OUT);
				// default request : GET
				connection.setRequestMethod("GET");
				connection.disconnect();

				if (isStop)
					return;

				int responseCode = connection.getResponseCode();
				HttpUtils.printHeader(connection);
				if (responseCode == 200)
				{
					mFileSize = connection.getContentLength();
					if (MemorySpaceCheck.hasSDEnoughMemory(mStoreFile.getParent(), mFileSize))
					{
						checkLocalStoreFile(mFileSize);
						onPreExecute(mFileSize);
						mDownloadSize = mDBHelper.queryDownloadSize(mFileName);
						long block = mFileSize % THREAD_COUNT == 0 ? mFileSize / THREAD_COUNT : mFileSize / THREAD_COUNT + 1;
						for (int i = 0; i < THREAD_COUNT; i++)
						{
							mDownloadThreads[i] = new DownloadThread(mContext, this, i, block, mFileSize);
							mDownloadThreads[i].start();
						}

						while (!isStop && !isFinished)
						{
							isFinished = true;
							for (int i = 0; i < THREAD_COUNT; i++)
							{
								if (mDownloadThreads[i] != null && !mDownloadThreads[i].isFinished())
								{
									isFinished = false;
								}
							}
						}

						if (isFinished)
						{
							if (!mStoreFile.exists())
								throw new IllegalStateException("Download failed, Storage file is not exist.");
							mState = STATE_SUCCESS;
							DownloaderManager.getDownloaderList().remove(this);
							onSuccess();
						}
					}
					else
					{
						sendErrorMsg(new SpaceNotEnoughException());
					}
				}
				else
				{
					sendErrorMsg(new ServerConnectException(responseCode));
				}
			}
			else
			{
				sendErrorMsg(new URLInvalidException());
			}
		}
		catch (Exception e)
		{
			sendErrorMsg(new DownloadError(e));
		}
	}

	/**
	 * 本地储存文件检测
	 *
	 * @param fileSize 下载文件长度
	 * @throws Exception
     */
	private void checkLocalStoreFile(long fileSize) throws Exception
	{
		if (!mStoreFile.exists())
		{
			mDBHelper.deleteDownloadInfo(mFileName);

			if (!mStoreFile.getParentFile().exists() && !mStoreFile.getParentFile().mkdirs())
				throw new IllegalStateException("Failed to open downloader space.");

			if (!mStoreFile.createNewFile())
				throw new IllegalStateException("Failed to create storage file.");
		}
		RandomAccessFile accessFile = new RandomAccessFile(mStoreFile, "rwd");
		accessFile.setLength(fileSize);
		accessFile.close();
	}

	/**
	 * 更新总下载长度
	 * 
	 * @param size 一次文件流的长度
	 */
	protected synchronized void append(long size)
	{
		mDownloadSize += size;
		onProgressChange(mFileSize, mDownloadSize);
	}

	@Override
	public void onPreExecute(final long fileSize)
	{
		if (isStop)
			return;

		mResponsePoster.execute(new Runnable()
		{
			@Override
			public void run()
			{
				if (mDownloaderListener != null)
					mDownloaderListener.onPreExecute(fileSize);
			}
		});
	}

	@Override
	public void onProgressChange(final long fileSize, final long downloadedSize)
	{
		mResponsePoster.execute(new Runnable()
		{
			@Override
			public void run()
			{
				if (mDownloaderListener != null)
					mDownloaderListener.onProgressChange(fileSize, downloadedSize);
			}
		});
	}

	@Override
	public void onCancel()
	{
		mResponsePoster.execute(new Runnable()
		{
			@Override
			public void run()
			{
				if (mDownloaderListener != null)
					mDownloaderListener.onCancel();
			}
		});
	}

	@Override
	public void onError(final DownloadError error)
	{
		if (isStop)
			return;

		mResponsePoster.execute(new Runnable()
		{
			@Override
			public void run()
			{
				if (mDownloaderListener != null)
					mDownloaderListener.onError(error);
			}
		});
	}

	@Override
	public void onSuccess()
	{
		mResponsePoster.execute(new Runnable()
		{
			@Override
			public void run()
			{
				if (mDownloaderListener != null)
					mDownloaderListener.onSuccess();
			}
		});
	}

	/**
	 * 下载异常，一个下载线程停止，其他相关的线程也停止
	 *
	 * @param error 异常
     */
	protected void sendErrorMsg(DownloadError error)
	{
		mState = STATE_ERROR;
		DownloaderManager.getDownloaderList().remove(this);
		// 暂停、错误冲突：暂停就不打印错误，否则打印，下面语句顺序不能改变
		onError(error);
		isStop = true;
	}

	/**
	 * 恢复任务
	 */
	public void resume()
	{
		mState = STATE_DOWNLOADING;
		isStop = false;
		DownloaderManager.addTask(this);
	}

	/**
	 * 暂停任务
	 */
	public void pause()
	{
		mState = STATE_PAUSE;
		isStop = true;
		onCancel();
		DownloaderManager.getDownloaderList().remove(this);
	}

	/**
	 * 退出暂停任务
	 */
	protected void destroy()
	{
		mState = STATE_PAUSE;
		isStop = true;
		for (DownloadThread taskThread : mDownloadThreads)
		{
			taskThread.updateDatabase();
		}
	}

	/**
	 * 删除任务
	 */
	public void discard()
	{
		mState = STATE_DISCARD;
		isStop = true;
		mDBHelper.deleteDownloadInfo(mFileName);
		mStoreFile.delete();
		DownloaderManager.getDownloaderList().remove(this);
	}

	/**
	 * 获取下载状态
	 *
	 * @return 下载状态
     */
	public int getState()
	{
		return mState;
	}

	/**
	 * 是否停止下载
	 *
	 * @return
     */
	public boolean isStop()
	{
		return isStop;
	}

	/**
	 * 获取文件名
	 *
	 * @return 文件名
     */
	public String getFileName()
	{
		return mFileName;
	}

	/**
	 * 获取文件
	 *
	 * @return File类型
     */
	public File getStoreFile()
	{
		return mStoreFile;
	}

	/**
	 * 获取下载链接
	 *
	 * @return 下载链接
     */
	public String getFileUrl()
	{
		return mFileUrl;
	}
}
