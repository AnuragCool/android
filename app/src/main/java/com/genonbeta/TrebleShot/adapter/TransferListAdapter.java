/*
 * Copyright (C) 2019 Veli Tasalı
 *
 * This program is free software; you can redistribute it and/or
 * modify it under the terms of the GNU General Public License
 * as published by the Free Software Foundation; either version 2
 * of the License, or (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program; if not, write to the Free Software
 * Foundation, Inc., 51 Franklin Street, Fifth Floor, Boston, MA  02110-1301, USA.
 */

package com.genonbeta.TrebleShot.adapter;

import android.content.Context;
import android.content.res.ColorStateList;
import android.graphics.drawable.Drawable;
import android.net.Uri;
import android.os.Build;
import android.util.Log;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.ProgressBar;
import android.widget.TextView;

import androidx.annotation.ColorInt;
import androidx.annotation.DrawableRes;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.collection.ArrayMap;
import androidx.core.content.ContextCompat;
import androidx.core.graphics.drawable.DrawableCompat;
import androidx.core.widget.ImageViewCompat;

import com.genonbeta.TrebleShot.GlideApp;
import com.genonbeta.TrebleShot.R;
import com.genonbeta.TrebleShot.database.AccessDatabase;
import com.genonbeta.TrebleShot.object.NetworkDevice;
import com.genonbeta.TrebleShot.object.TransferGroup;
import com.genonbeta.TrebleShot.object.TransferObject;
import com.genonbeta.TrebleShot.util.AppUtils;
import com.genonbeta.TrebleShot.util.FileUtils;
import com.genonbeta.TrebleShot.util.MimeIconUtils;
import com.genonbeta.TrebleShot.util.TextUtils;
import com.genonbeta.TrebleShot.widget.GroupEditableListAdapter;
import com.genonbeta.android.database.SQLQuery;
import com.genonbeta.android.database.exception.ReconstructionFailedException;
import com.genonbeta.android.framework.io.DocumentFile;
import com.genonbeta.android.framework.io.LocalDocumentFile;
import com.genonbeta.android.framework.util.MathUtils;
import com.genonbeta.android.framework.util.listing.ComparableMerger;
import com.genonbeta.android.framework.util.listing.Merger;

import java.io.File;
import java.text.NumberFormat;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Created by: veli
 * Date: 4/15/17 12:29 PM
 */

public class TransferListAdapter
        extends GroupEditableListAdapter<TransferListAdapter.AbstractGenericItem, GroupEditableListAdapter.GroupViewHolder>
        implements GroupEditableListAdapter.GroupLister.CustomGroupLister<TransferListAdapter.AbstractGenericItem>
{
    //public static final int MODE_SORT_BY_DEFAULT = MODE_SORT_BY_NAME - 1;
    public static final int MODE_GROUP_BY_DEFAULT = MODE_GROUP_BY_NOTHING + 1;

    private SQLQuery.Select mSelect;
    private String mPath;
    private NetworkDevice mDevice;
    private TransferGroup mGroup = new TransferGroup();
    private PathChangedListener mListener;
    private NumberFormat mPercentFormat;

    @ColorInt
    private int mColorPending;
    private int mColorDone;
    private int mColorError;

    public TransferListAdapter(Context context)
    {
        super(context, MODE_GROUP_BY_DEFAULT);

        mPercentFormat = NumberFormat.getPercentInstance();
        mColorPending = ContextCompat.getColor(context, AppUtils.getReference(context, R.attr.colorControlNormal));
        mColorDone = ContextCompat.getColor(context, AppUtils.getReference(context, R.attr.colorAccent));
        mColorError = ContextCompat.getColor(context, AppUtils.getReference(context, R.attr.colorError));

        setSelect(new SQLQuery.Select(AccessDatabase.TABLE_TRANSFER));
    }

    @Override
    protected void onLoad(GroupLister<AbstractGenericItem> lister)
    {
        final boolean loadThumbnails = AppUtils.getDefaultPreferences(getContext())
                .getBoolean("load_thumbnails", true);

        try {
            AppUtils.getDatabase(getContext()).reconstruct(mGroup);
        } catch (ReconstructionFailedException e) {
            e.printStackTrace();
            return;
        }

        boolean hasIncoming = false;
        Map<String, TransferFolder> folders = new ArrayMap<>();
        List<GenericTransferItem> files = new ArrayList<>();
        String currentPath = getPath();
        currentPath = currentPath == null || currentPath.length() == 0 ? null : currentPath;

        {
            List<TransferGroup.Assignee> assignees = AppUtils.getDatabase(getContext())
                    .castQuery(new SQLQuery.Select(AccessDatabase.TABLE_TRANSFERASSIGNEE)
                            .setWhere(AccessDatabase.FIELD_TRANSFERASSIGNEE_GROUPID + "=?",
                                    String.valueOf(mGroup.id)), TransferGroup.Assignee.class);
        }

        NetworkDevice filterDevice = mDevice;
        SQLQuery.Select transferSelect = new SQLQuery.Select(AccessDatabase.TABLE_TRANSFER);
        StringBuilder transferWhere = new StringBuilder(AccessDatabase.FIELD_TRANSFER_GROUPID + "=?");
        List<String> transferArgs = new ArrayList<>();

        transferArgs.add(String.valueOf(mGroup.id));

        if (currentPath != null) {
            transferWhere.append(" AND (" + AccessDatabase.FIELD_TRANSFER_DIRECTORY + "=? OR "
                    + AccessDatabase.FIELD_TRANSFER_DIRECTORY + " LIKE ?)");

            transferArgs.add(currentPath);
            transferArgs.add(currentPath + File.separator + "%");
        }

        transferSelect.where = transferWhere.toString();
        transferSelect.whereArgs = new String[transferArgs.size()];
        transferArgs.toArray(transferSelect.whereArgs);

        List<GenericTransferItem> derivedList = AppUtils.getDatabase(getContext()).castQuery(
                transferSelect, GenericTransferItem.class);

        // we first get the default files
        for (GenericTransferItem object : derivedList) {
            boolean isIncoming = TransferObject.Type.INCOMING.equals(object.type);
            object.directory = object.directory == null || object.directory.length() == 0 ? null : object.directory;

            if (currentPath != null && object.directory == null)
                continue;

            if ((currentPath == null && object.directory == null)
                    || object.directory.equals(currentPath)) {
                try {
                    if (!loadThumbnails)
                        object.setSupportThumbnail(false);
                    else {
                        String[] format = object.mimeType.split(File.separator);

                        if (format.length > 0 && ("image".equals(format[0]) || "video".equals(format[0]))) {
                            DocumentFile documentFile = null;

                            if (TransferObject.Type.OUTGOING.equals(object.type))
                                documentFile = FileUtils.fromUri(getContext(), Uri.parse(object.file));
                            else if (TransferObject.Flag.DONE.equals(object.getFlag()))
                                documentFile = FileUtils.getIncomingPseudoFile(getContext(), object, mGroup, false);

                            if (documentFile != null && documentFile.exists()) {
                                object.setFile(documentFile);
                                object.setSupportThumbnail(true);
                            }
                        } else
                            object.setSupportThumbnail(false);
                    }
                } catch (Exception e) {
                    e.printStackTrace();
                }

                files.add(object);
            } else if (currentPath == null || (object.directory.startsWith(currentPath))) {
                int pathToErase = currentPath == null ? 0 : currentPath.length() + File.separator.length();
                String cleanedPath = object.directory.substring(pathToErase);
                int slashPos = cleanedPath.indexOf(File.separator);

                if (slashPos != -1)
                    cleanedPath = cleanedPath.substring(0, slashPos);

                TransferFolder transferFolder = folders.get(cleanedPath);

                if (transferFolder == null) {
                    transferFolder = new TransferFolder(mGroup.id, cleanedPath, currentPath != null
                            ? currentPath + File.separator + cleanedPath
                            : cleanedPath);

                    folders.put(cleanedPath, transferFolder);
                }

                if (object.isComplete(this)) {
                    transferFolder.filesReceived++;
                } else if (object.hasIssues(this)) {
                    transferFolder.setHasIssues(true);
                } else {
                    if (TransferObject.Type.INCOMING.equals(object.type))
                        if (TransferObject.Flag.IN_PROGRESS.equals(object.getFlag()))
                            transferFolder.bytesReceived += object.getFlag().getBytesValue();
                }

                transferFolder.filesTotal++;
                transferFolder.bytesTotal += object.size;
            }

            if (!hasIncoming && TransferObject.Type.INCOMING.equals(object.type))
                hasIncoming = true;
        }

        StorageStatusItem storageItem = null;

        if (currentPath == null)
            if (hasIncoming) {
                try {
                    TransferGroup group = new TransferGroup(mGroup.id);
                    AppUtils.getDatabase(getContext()).reconstruct(group);
                    DocumentFile savePath = FileUtils.getSavePath(getContext(), group);

                    storageItem = new StorageStatusItem();
                    storageItem.directory = savePath.getUri().toString();
                    storageItem.name = savePath.getName();

                    if (savePath instanceof LocalDocumentFile) {
                        File saveFile = ((LocalDocumentFile) savePath).getFile();
                        storageItem.bytesTotal = saveFile.getTotalSpace();
                        storageItem.bytesFree = saveFile.getFreeSpace(); // return used space
                    } else {
                        storageItem.bytesTotal = -1;
                        storageItem.bytesFree = -1;
                    }

                    lister.offerObliged(this, storageItem);
                } catch (Exception e) {
                    e.printStackTrace();
                }
            }

        DetailsTransferFolder statusItem = new DetailsTransferFolder(mGroup.id, currentPath == null
                ? (filterDevice == null ? getContext().getString(R.string.text_home) : filterDevice.nickname)
                : currentPath.contains(File.separator) ? currentPath.substring(currentPath.lastIndexOf(File.separator) + 1) : currentPath, currentPath);

        lister.offerObliged(this, statusItem);

        for (TransferFolder folder : folders.values()) {
            statusItem.filesTotal += folder.filesTotal;
            statusItem.filesReceived += folder.filesReceived;
            statusItem.bytesTotal += folder.bytesTotal;
            statusItem.bytesReceived += folder.bytesReceived;

            if (folder.hasIssues(this))
                statusItem.setHasIssues(true);

            lister.offerObliged(this, folder);
        }

        for (GenericTransferItem file : files) {
            if (file.isComplete(this)) {
                statusItem.filesReceived++;
                statusItem.percentage += file.getPercent(this);
            } else if (file.hasIssues(this)) {
                statusItem.setHasIssues(true);
            }

            statusItem.filesTotal++;
            statusItem.bytesTotal += file.size;

            lister.offerObliged(this, file);
        }

        if (storageItem != null)
            storageItem.bytesRequired = statusItem.bytesTotal - statusItem.bytesReceived;
    }

    @Override
    protected GenericTransferItem onGenerateRepresentative(String representativeText)
    {
        return new GenericTransferItem(representativeText);
    }

    @Override
    public boolean onCustomGroupListing(GroupLister<AbstractGenericItem> lister, int mode, AbstractGenericItem object)
    {
        if (mode == MODE_GROUP_BY_DEFAULT)
            lister.offer(object, new GroupEditableTransferObjectMerger(object, this));
        else
            return false;

        return true;
    }

    @Override
    public int compareItems(int sortingCriteria, int sortingOrder, AbstractGenericItem objectOne, AbstractGenericItem objectTwo)
    {
        //if (sortingCriteria == MODE_SORT_BY_DEFAULT)
        //    return MathUtils.compare(objectTwo.requestId, objectOne.requestId);

        return 1;
    }

    @Override
    public GroupLister<AbstractGenericItem> createLister(List<AbstractGenericItem> loadedList, int groupBy)
    {
        return super.createLister(loadedList, groupBy)
                .setCustomLister(this);
    }

    public NetworkDevice getDevice()
    {
        return mDevice;
    }

    public boolean setDeviceId(String id)
    {
        if (id == null) {
            mDevice = null;
            return true;
        }

        NetworkDevice device = new NetworkDevice(id);

        try {
            AppUtils.getDatabase(getContext()).reconstruct(device);
            mDevice = device;

            return true;
        } catch (Exception e) {
            // do nothing
        }

        return false;
    }

    public long getGroupId()
    {
        return mGroup.id;
    }

    public void setGroupId(long groupId)
    {
        mGroup.id = groupId;
    }

    public String getPath()
    {
        return mPath;
    }

    public void setPath(String path)
    {
        mPath = path;

        if (mListener != null)
            mListener.onPathChange(path);
    }

    private NumberFormat getPercentFormat()
    {
        return mPercentFormat;
    }

    @Override
    public String getRepresentativeText(Merger merger)
    {
        if (merger instanceof GroupEditableTransferObjectMerger) {
            switch (((GroupEditableTransferObjectMerger) merger).getType()) {
                case STATUS:
                    return getContext().getString(R.string.text_transactionDetails);
                case FOLDER:
                    return getContext().getString(R.string.text_folder);
                case FILE_ERROR:
                    return getContext().getString(R.string.text_flagInterrupted);
                case FILE_ONGOING:
                    return getContext().getString(R.string.text_taskOngoing);
                default:
                    return getContext().getString(R.string.text_file);
            }
        }

        return super.getRepresentativeText(merger);
    }

    public SQLQuery.Select getSelect()
    {
        return mSelect;
    }

    public TransferListAdapter setSelect(SQLQuery.Select select)
    {
        if (select != null)
            mSelect = select;

        return this;
    }

    public void setPathChangedListener(PathChangedListener listener)
    {
        mListener = listener;
    }

    @NonNull
    @Override
    public GroupEditableListAdapter.GroupViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType)
    {
        if (viewType == VIEW_TYPE_REPRESENTATIVE)
            return new GroupViewHolder(getInflater().inflate(R.layout.layout_list_title, parent, false), R.id.layout_list_title_text);

        return new GroupEditableListAdapter.GroupViewHolder(getInflater().inflate(R.layout.list_transfer, parent, false));
    }

    @Override
    public void onBindViewHolder(@NonNull final GroupEditableListAdapter.GroupViewHolder holder, int position)
    {
        try {
            final AbstractGenericItem object = getItem(position);

            if (!holder.tryBinding(object)) {
                final View parentView = holder.getView();

                @ColorInt
                int appliedColor;
                int percentage = (int) (object.getPercent(this) * 100);
                ProgressBar progressBar = parentView.findViewById(R.id.progressBar);
                ImageView thumbnail = parentView.findViewById(R.id.thumbnail);
                ImageView image = parentView.findViewById(R.id.image);
                ImageView sIcon = parentView.findViewById(R.id.statusIcon);
                TextView titleText = parentView.findViewById(R.id.text);
                TextView firstText = parentView.findViewById(R.id.text2);
                TextView secondText = parentView.findViewById(R.id.text3);
                TextView thirdText = parentView.findViewById(R.id.text4);

                parentView.setSelected(object.isSelectableSelected());

                if (object.isComplete(this))
                    appliedColor = mColorDone;
                else if (object.hasIssues(this))
                    appliedColor = mColorError;
                else
                    appliedColor = mColorPending;

                titleText.setText(object.name);
                firstText.setText(object.getFirstText(this));
                secondText.setText(object.getSecondText(this));
                thirdText.setText(object.getThirdText(this));

                object.handleStatusIcon(sIcon, mGroup);
                ImageViewCompat.setImageTintList(sIcon, ColorStateList.valueOf(appliedColor));
                progressBar.setMax(100);
                progressBar.setProgress(percentage <= 0 ? 1 : percentage);

                thirdText.setTextColor(appliedColor);
                ImageViewCompat.setImageTintList(image, ColorStateList.valueOf(appliedColor));

                if (Build.VERSION.SDK_INT < Build.VERSION_CODES.LOLLIPOP) {
                    Drawable wrapDrawable = DrawableCompat.wrap(progressBar.getProgressDrawable());

                    DrawableCompat.setTint(wrapDrawable, appliedColor);
                    progressBar.setProgressDrawable(DrawableCompat.unwrap(wrapDrawable));
                } else
                    progressBar.setProgressTintList(ColorStateList.valueOf(appliedColor));

                boolean supportThumbnail = object.loadThumbnail(thumbnail);

                progressBar.setVisibility(!supportThumbnail || !object.isComplete(this)
                        ? View.VISIBLE
                        : View.GONE);

                if (supportThumbnail)
                    image.setImageDrawable(null);
                else {
                    image.setImageResource(object.getIconRes());
                    thumbnail.setImageDrawable(null);
                }
            }
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    public interface PathChangedListener
    {
        void onPathChange(String path);
    }

    interface StatusItem
    {

    }

    abstract public static class AbstractGenericItem
            extends TransferObject
            implements GroupEditableListAdapter.GroupEditable
    {
        public int viewType;

        public String representativeText;

        public AbstractGenericItem()
        {
        }

        public AbstractGenericItem(String representativeText)
        {
            this.viewType = VIEW_TYPE_REPRESENTATIVE;
            setRepresentativeText(representativeText);
        }

        @Override
        public boolean applyFilter(String[] filteringKeywords)
        {
            for (String keyword : filteringKeywords)
                if (name != null && name.toLowerCase().contains(keyword.toLowerCase()))
                    return true;

            return false;
        }

        abstract public boolean hasIssues(TransferListAdapter adapter);

        abstract public boolean isComplete(TransferListAdapter adapter);

        @DrawableRes
        abstract public int getIconRes();

        abstract public double getPercent(TransferListAdapter adapter);

        abstract public boolean loadThumbnail(ImageView imageView);

        abstract public void handleStatusIcon(ImageView imageView, TransferGroup group);

        abstract public String getFirstText(TransferListAdapter adapter);

        abstract public String getSecondText(TransferListAdapter adapter);

        abstract public String getThirdText(TransferListAdapter adapter);

        @Override
        public int getRequestCode()
        {
            return 0;
        }

        @Override
        public int getViewType()
        {
            return this.viewType;
        }

        @Override
        public String getRepresentativeText()
        {
            return this.representativeText;
        }

        @Override
        public void setRepresentativeText(CharSequence representativeText)
        {
            this.representativeText = String.valueOf(representativeText);
        }

        @Override
        public boolean isGroupRepresentative()
        {
            return this.viewType == VIEW_TYPE_REPRESENTATIVE;
        }

        @Override
        public void setDate(long date)
        {
            // stamp
        }

        @Override
        public boolean setSelectableSelected(boolean selected)
        {
            return !isGroupRepresentative() && super.setSelectableSelected(selected);
        }

        @Override
        public void setSize(long size)
        {
            this.size = size;
        }
    }

    public static class GenericTransferItem extends AbstractGenericItem
    {
        private DocumentFile mFile;
        private boolean mSupportThumbnail;

        public GenericTransferItem()
        {
        }

        GenericTransferItem(String representativeText)
        {
            this.viewType = VIEW_TYPE_REPRESENTATIVE;
            setRepresentativeText(representativeText);
        }

        @Override
        public boolean applyFilter(String[] filteringKeywords)
        {
            if (super.applyFilter(filteringKeywords))
                return true;

            for (String keyword : filteringKeywords)
                if (mimeType.toLowerCase().contains(keyword.toLowerCase()))
                    return true;

            return false;
        }

        @Override
        public boolean hasIssues(TransferListAdapter adapter)
        {
            if (Type.OUTGOING.equals(type)) {
                if (adapter.getDevice() == null)
                    synchronized (mSenderFlagList) {
                        for (Flag flag : mSenderFlagList.values())
                            if (Flag.INTERRUPTED.equals(flag))
                                return true;
                    }
                else
                    return Flag.INTERRUPTED.equals(getFlag(adapter.getDevice().id));
            } else
                return Flag.INTERRUPTED.equals(getFlag());

            return false;
        }

        @Override
        public boolean isComplete(TransferListAdapter adapter)
        {
            if (Type.INCOMING.equals(type))
                return Flag.DONE.equals(getFlag());

            synchronized (mSenderFlagList) {
                if (adapter.getDevice() == null) {
                    for (Flag flag : mSenderFlagList.values())
                        if (!Flag.DONE.equals(flag))
                            return false;

                    return mSenderFlagList.size() > 0;
                } else
                    return Flag.DONE.equals(getFlag(adapter.getDevice().id));
            }
        }

        @Override
        public int getIconRes()
        {
            return MimeIconUtils.loadMimeIcon(mimeType);
        }

        @Override
        public double getPercent(TransferListAdapter adapter)
        {
            if (Type.INCOMING.equals(type)) {
                if (Flag.DONE.equals(getFlag()))
                    return 1;

                return getFlag().getBytesValue() > 0
                        ? Long.valueOf(getFlag().getBytesValue()).doubleValue() / Long.valueOf(size).doubleValue()
                        : 0;
            }

            if (mSenderFlagList.size() < 1)
                return 0;

            long receivedSize = 0;
            int totalFlags;
            boolean allCompleted = true;

            synchronized (mSenderFlagList) {
                if (adapter.getDevice() == null) {
                    totalFlags = mSenderFlagList.size();

                    for (Flag flag : mSenderFlagList.values()) {
                        receivedSize += flag.getBytesValue();
                        if (allCompleted && !Flag.DONE.equals(flag))
                            allCompleted = false;
                    }

                    if (allCompleted)
                        return 1;

                    return receivedSize > 1
                            ? Long.valueOf(receivedSize).doubleValue() / (Long.valueOf(size).doubleValue() * totalFlags)
                            : 0;
                } else {
                    return getFlag(adapter.getDevice().id).getBytesValue() > 0
                            ? Long.valueOf(getFlag(adapter.getDevice().id).getBytesValue()).doubleValue()
                            / Long.valueOf(size).doubleValue()
                            : 0;
                }
            }
        }

        @Override
        public void handleStatusIcon(ImageView imageView, TransferGroup group)
        {
            imageView.setVisibility(View.VISIBLE);
            imageView.setImageResource(Type.INCOMING.equals(type)
                    ? R.drawable.ic_arrow_down_white_24dp
                    : R.drawable.ic_arrow_up_white_24dp);
        }

        @Override
        public String getFirstText(TransferListAdapter adapter)
        {
            return FileUtils.sizeExpression(size, false);
        }

        @Override
        public String getSecondText(TransferListAdapter adapter)
        {
            // FIXME: 7/17/19 Device name was here
            return adapter.getContext().getString(R.string.text_unknown);
        }

        @Override
        public String getThirdText(TransferListAdapter adapter)
        {
            return TextUtils.getTransactionFlagString(adapter.getContext(), this, adapter.getPercentFormat());
        }

        @Override
        public boolean loadThumbnail(ImageView imageView)
        {
            if (mFile != null && mSupportThumbnail && mFile.exists()) {
                GlideApp.with(imageView.getContext())
                        .load(mFile.getUri())
                        .error(getIconRes())
                        .override(160)
                        .centerCrop()
                        .into(imageView);

                return true;
            }

            return false;
        }

        public void setFile(DocumentFile file)
        {
            mFile = file;
        }

        void setSupportThumbnail(boolean support)
        {
            mSupportThumbnail = support;
        }
    }

    public static class TransferFolder extends AbstractGenericItem
    {
        int filesTotal = 0;
        int filesReceived = 0;
        long bytesTotal = 0;
        long bytesReceived = 0;
        double percentage = 0;
        private boolean mHasIssues = false;

        TransferFolder(long groupId, String friendlyName, String directory)
        {
            this.groupId = groupId;
            this.name = friendlyName;
            this.directory = directory;
        }

        @Override
        public boolean hasIssues(TransferListAdapter adapter)
        {
            return mHasIssues;
        }

        @Override
        public boolean isComplete(TransferListAdapter adapter)
        {
            return filesTotal == filesReceived && filesTotal != 0;
        }

        @Override
        public int getIconRes()
        {
            return R.drawable.ic_folder_white_24dp;
        }

        @Override
        public double getPercent(TransferListAdapter adapter)
        {
            if (filesTotal == 0 || filesReceived == 0)
                return 0;
            else if (filesReceived == filesTotal)
                return 1;

            return percentage / filesTotal;
        }

        @Override
        public void handleStatusIcon(ImageView imageView, TransferGroup group)
        {
            imageView.setVisibility(View.GONE);
        }

        @Override
        public String getFirstText(TransferListAdapter adapter)
        {
            return FileUtils.sizeExpression(bytesTotal, false);
        }

        @Override
        public String getSecondText(TransferListAdapter adapter)
        {
            return adapter.getContext()
                    .getString(R.string.text_transferStatusFiles, filesReceived, filesTotal);
        }

        @Override
        public String getThirdText(TransferListAdapter adapter)
        {
            return adapter.getPercentFormat().format(getPercent(adapter));
        }

        @Override
        public SQLQuery.Select getWhere()
        {
            return new SQLQuery.Select(AccessDatabase.TABLE_TRANSFER)
                    .setWhere(AccessDatabase.FIELD_TRANSFER_GROUPID + "=? AND ("
                                    + AccessDatabase.FIELD_TRANSFER_DIRECTORY + " LIKE ? OR "
                                    + AccessDatabase.FIELD_TRANSFER_DIRECTORY + " = ?)",
                            String.valueOf(this.groupId),
                            this.directory + File.separator + "%",
                            this.directory);
        }

        @Override
        public boolean equals(Object obj)
        {
            return obj instanceof TransferFolder
                    && directory != null
                    && directory.equals(((TransferFolder) obj).directory);
        }

        @Override
        public long getId()
        {
            return directory.hashCode();
        }

        @Override
        public void setId(long id)
        {
            super.setId(id);
            Log.d(TransferListAdapter.class.getSimpleName(), "setId(): This method should not be invoked");
        }

        @Override
        public boolean loadThumbnail(ImageView imageView)
        {
            return false;
        }

        void setHasIssues(boolean hasIssues)
        {
            mHasIssues = hasIssues;
        }
    }

    public static class DetailsTransferFolder extends TransferFolder implements StatusItem
    {
        DetailsTransferFolder(long groupId, String friendlyName, String directory)
        {
            super(groupId, friendlyName, directory);
        }

        @Override
        public void handleStatusIcon(ImageView imageView, TransferGroup group)
        {
            if (group.isServedOnWeb) {
                imageView.setVisibility(View.VISIBLE);
                imageView.setImageResource(R.drawable.ic_web_white_24dp);
            } else
                super.handleStatusIcon(imageView, group);
        }

        @Override
        public int getIconRes()
        {
            return R.drawable.ic_info_white_24dp;
        }

        @Override
        public long getId()
        {
            return (directory != null ? directory : name).hashCode();
        }

        @Override
        public boolean isSelectableSelected()
        {
            return false;
        }

        @Override
        public boolean setSelectableSelected(boolean selected)
        {
            return false;
        }
    }

    public static class StorageStatusItem extends AbstractGenericItem implements StatusItem
    {
        long bytesTotal = 0;
        long bytesFree = 0;
        long bytesRequired = 0;

        @Override
        public boolean hasIssues(TransferListAdapter adapter)
        {
            return bytesFree < bytesRequired && bytesFree != -1;
        }

        @Override
        public boolean isComplete(TransferListAdapter adapter)
        {
            return bytesFree == -1 || !hasIssues(adapter);
        }

        @Override
        public boolean isSelectableSelected()
        {
            return false;
        }

        @Override
        public int getIconRes()
        {
            return R.drawable.ic_save_white_24dp;
        }

        @Override
        public long getId()
        {
            return (directory != null ? directory : name).hashCode();
        }

        @Override
        public double getPercent(TransferListAdapter adapter)
        {
            return bytesTotal <= 0 || bytesFree <= 0
                    ? 0
                    : Long.valueOf(bytesTotal - bytesFree).doubleValue() / Long.valueOf(bytesTotal).doubleValue();
        }

        @Override
        public void handleStatusIcon(ImageView imageView, TransferGroup group)
        {
            imageView.setVisibility(View.GONE);
        }

        @Override
        public String getFirstText(TransferListAdapter adapter)
        {
            return bytesFree == -1
                    ? adapter.getContext().getString(R.string.text_unknown)
                    : FileUtils.sizeExpression(bytesFree, false);
        }

        @Override
        public String getSecondText(TransferListAdapter adapter)
        {
            return adapter.getContext().getString(R.string.text_savePath);
        }

        @Override
        public String getThirdText(TransferListAdapter adapter)
        {
            return adapter.getPercentFormat().format(getPercent(adapter));
        }

        @Override
        public boolean loadThumbnail(ImageView imageView)
        {
            return false;
        }

        @Override
        public boolean setSelectableSelected(boolean selected)
        {
            return false;
        }
    }

    public static class GroupEditableTransferObjectMerger extends ComparableMerger<AbstractGenericItem>
    {
        private Type mType;

        GroupEditableTransferObjectMerger(AbstractGenericItem holder, TransferListAdapter adapter)
        {
            if (holder instanceof StatusItem)
                mType = Type.STATUS;
            else if (holder instanceof TransferFolder)
                mType = Type.FOLDER;
            else {
                if (holder.hasIssues(adapter))
                    mType = Type.FILE_ERROR;
                else {
                    mType = Type.FILE;

                    if (TransferObject.Type.INCOMING.equals(holder.type)) {
                        if (TransferObject.Flag.IN_PROGRESS.equals(holder.getFlag()))
                            mType = Type.FILE_ONGOING;
                    } else
                        for (TransferObject.Flag flag : holder.getFlags())
                            if (TransferObject.Flag.IN_PROGRESS.equals(flag)) {
                                mType = Type.FILE_ONGOING;
                                break;
                            }
                }
            }
        }

        @Override
        public boolean equals(Object obj)
        {
            return obj instanceof GroupEditableTransferObjectMerger
                    && ((GroupEditableTransferObjectMerger) obj).getType().equals(getType());
        }

        public Type getType()
        {
            return mType;
        }

        @Override
        public int compareTo(@NonNull ComparableMerger<AbstractGenericItem> o)
        {
            if (o instanceof GroupEditableTransferObjectMerger)
                return MathUtils.compare(((GroupEditableTransferObjectMerger) o).getType().ordinal(), getType().ordinal());

            return 1;
        }

        public enum Type
        {
            STATUS,
            FOLDER,
            FILE_ONGOING,
            FILE_ERROR,
            FILE,
        }
    }
}
