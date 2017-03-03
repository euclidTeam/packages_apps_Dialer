/*
 * Copyright (C) 2016 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.android.dialer.callcomposer;

import static android.app.Activity.RESULT_OK;

import android.Manifest.permission;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.database.Cursor;
import android.net.Uri;
import android.os.Bundle;
import android.provider.Settings;
import android.support.annotation.NonNull;
import android.support.annotation.Nullable;
import android.support.v4.app.LoaderManager.LoaderCallbacks;
import android.support.v4.content.ContextCompat;
import android.support.v4.content.CursorLoader;
import android.support.v4.content.Loader;
import android.view.LayoutInflater;
import android.view.View;
import android.view.View.OnClickListener;
import android.view.ViewGroup;
import android.widget.GridView;
import android.widget.ImageView;
import android.widget.TextView;
import com.android.dialer.callcomposer.util.CopyAndResizeImageTask;
import com.android.dialer.callcomposer.util.CopyAndResizeImageTask.Callback;
import com.android.dialer.common.LogUtil;
import com.android.dialer.logging.Logger;
import com.android.dialer.logging.nano.DialerImpression;
import com.android.dialer.util.PermissionsUtil;
import java.io.File;

/** Fragment used to compose call with image from the user's gallery. */
public class GalleryComposerFragment extends CallComposerFragment
    implements LoaderCallbacks<Cursor>, OnClickListener {

  private static final int RESULT_LOAD_IMAGE = 1;
  private static final int RESULT_OPEN_SETTINGS = 2;

  private GalleryGridAdapter adapter;
  private GridView galleryGridView;
  private View permissionView;
  private View allowPermission;

  private String[] permissions = new String[] {permission.READ_EXTERNAL_STORAGE};
  private CursorLoader cursorLoader;
  private GalleryGridItemData selectedData = null;
  private boolean selectedDataIsCopy;

  public static GalleryComposerFragment newInstance() {
    return new GalleryComposerFragment();
  }

  @Nullable
  @Override
  public View onCreateView(
      LayoutInflater inflater, @Nullable ViewGroup container, @Nullable Bundle bundle) {
    View view = inflater.inflate(R.layout.fragment_gallery_composer, container, false);
    galleryGridView = (GridView) view.findViewById(R.id.gallery_grid_view);
    permissionView = view.findViewById(R.id.permission_view);

    if (!PermissionsUtil.hasPermission(getContext(), permission.READ_EXTERNAL_STORAGE)) {
      Logger.get(getContext()).logImpression(DialerImpression.Type.STORAGE_PERMISSION_DISPLAYED);
      LogUtil.i("GalleryComposerFragment.onCreateView", "Permission view shown.");
      ImageView permissionImage = (ImageView) permissionView.findViewById(R.id.permission_icon);
      TextView permissionText = (TextView) permissionView.findViewById(R.id.permission_text);
      allowPermission = permissionView.findViewById(R.id.allow);

      allowPermission.setOnClickListener(this);
      permissionText.setText(R.string.gallery_permission_text);
      permissionImage.setImageResource(R.drawable.quantum_ic_photo_white_48);
      permissionImage.setColorFilter(
          ContextCompat.getColor(getContext(), R.color.dialer_theme_color));
      permissionView.setVisibility(View.VISIBLE);
    } else {
      setupGallery();
    }

    setTopView(galleryGridView);
    return view;
  }

  private void setupGallery() {
    adapter = new GalleryGridAdapter(getContext(), null, this);
    galleryGridView.setAdapter(adapter);
    getLoaderManager().initLoader(0 /* id */, null /* args */, this /* loaderCallbacks */);
  }

  @Override
  public Loader<Cursor> onCreateLoader(int id, Bundle args) {
    return cursorLoader = new GalleryCursorLoader(getContext());
  }

  @Override
  public void onLoadFinished(Loader<Cursor> loader, Cursor cursor) {
    adapter.swapCursor(cursor);
  }

  @Override
  public void onLoaderReset(Loader<Cursor> loader) {
    adapter.swapCursor(null);
  }

  @Override
  public void onClick(View view) {
    if (view == allowPermission) {
      // Checks to see if the user has permanently denied this permission. If this is their first
      // time seeing this permission or they've only pressed deny previously, they will see the
      // permission request. If they've permanently denied the permission, they will be sent to
      // Dialer settings in order to enable the permission.
      if (PermissionsUtil.isFirstRequest(getContext(), permissions[0])
          || shouldShowRequestPermissionRationale(permissions[0])) {
        LogUtil.i("GalleryComposerFragment.onClick", "Storage permission requested.");
        Logger.get(getContext()).logImpression(DialerImpression.Type.STORAGE_PERMISSION_REQUESTED);
        requestPermissions(permissions, STORAGE_PERMISSION);
      } else {
        LogUtil.i("GalleryComposerFragment.onClick", "Settings opened to enable permission.");
        Logger.get(getContext()).logImpression(DialerImpression.Type.STORAGE_PERMISSION_SETTINGS);
        Intent intent = new Intent(Intent.ACTION_VIEW);
        intent.setAction(Settings.ACTION_APPLICATION_DETAILS_SETTINGS);
        intent.setData(Uri.parse("package:" + getContext().getPackageName()));
        startActivityForResult(intent, RESULT_OPEN_SETTINGS);
      }
      return;
    } else {
      GalleryGridItemView itemView = ((GalleryGridItemView) view);
      if (itemView.isGallery()) {
        Intent intent = new Intent(Intent.ACTION_GET_CONTENT);
        intent.setType("image/*");
        intent.putExtra(Intent.EXTRA_MIME_TYPES, GalleryCursorLoader.ACCEPTABLE_IMAGE_TYPES);
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        startActivityForResult(intent, RESULT_LOAD_IMAGE);
      } else if (itemView.getData().equals(selectedData)) {
        setSelected(null, false);
      } else {
        setSelected(new GalleryGridItemData(itemView.getData()), false);
      }
    }
  }

  @Nullable
  public GalleryGridItemData getGalleryData() {
    return selectedData;
  }

  public GridView getGalleryGridView() {
    return galleryGridView;
  }

  @Override
  public void onActivityResult(int requestCode, int resultCode, Intent data) {
    super.onActivityResult(requestCode, resultCode, data);
    if (requestCode == RESULT_LOAD_IMAGE && resultCode == RESULT_OK && data != null) {
      prepareDataForAttachment(data);
    } else if (requestCode == RESULT_OPEN_SETTINGS
        && PermissionsUtil.hasPermission(getContext(), permission.READ_EXTERNAL_STORAGE)) {
      permissionView.setVisibility(View.GONE);
      setupGallery();
    }
  }

  private void setSelected(GalleryGridItemData data, boolean isCopy) {
    selectedData = data;
    selectedDataIsCopy = isCopy;
    adapter.setSelected(selectedData);
    getListener().composeCall(this);
  }

  @Override
  public boolean shouldHide() {
    return selectedData == null
        || selectedData.getFilePath() == null
        || selectedData.getMimeType() == null;
  }

  @Override
  public void onRequestPermissionsResult(
      int requestCode, @NonNull String[] permissions, @NonNull int[] grantResults) {
    if (permissions.length > 0 && permissions[0].equals(this.permissions[0])) {
      PermissionsUtil.permissionRequested(getContext(), permissions[0]);
    }
    if (requestCode == STORAGE_PERMISSION
        && grantResults.length > 0
        && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
      Logger.get(getContext()).logImpression(DialerImpression.Type.STORAGE_PERMISSION_GRANTED);
      LogUtil.i("GalleryComposerFragment.onRequestPermissionsResult", "Permission granted.");
      permissionView.setVisibility(View.GONE);
      setupGallery();
    } else if (requestCode == STORAGE_PERMISSION) {
      Logger.get(getContext()).logImpression(DialerImpression.Type.STORAGE_PERMISSION_DENIED);
      LogUtil.i("GalleryComposerFragment.onRequestPermissionsResult", "Permission denied.");
    }
  }

  public CursorLoader getCursorLoader() {
    return cursorLoader;
  }

  public boolean selectedDataIsCopy() {
    return selectedDataIsCopy;
  }

  private void prepareDataForAttachment(Intent data) {
    // We're using the builtin photo picker which supplies the return url as it's "data".
    String url = data.getDataString();
    if (url == null) {
      final Bundle extras = data.getExtras();
      if (extras != null) {
        final Uri uri = extras.getParcelable(Intent.EXTRA_STREAM);
        if (uri != null) {
          url = uri.toString();
        }
      }
    }

    // This should never happen, but just in case..
    // Guard against null uri cases for when the activity returns a null/invalid intent.
    if (url != null) {
      new CopyAndResizeImageTask(
              getContext(),
              Uri.parse(url),
              new Callback() {
                @Override
                public void onCopySuccessful(File file, String mimeType) {
                  setSelected(adapter.insertEntry(file.getAbsolutePath(), mimeType), true);
                }

                @Override
                public void onCopyFailed(Throwable throwable) {
                  // TODO(b/33753902)
                  LogUtil.e(
                      "GalleryComposerFragment.onFailure", "Data preparation failed", throwable);
                }
              })
          .execute();
    } else {
      // TODO(b/33753902)
    }
  }
}