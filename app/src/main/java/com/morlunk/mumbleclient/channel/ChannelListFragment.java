/*
 * Copyright (C) 2014 Andrew Comminos
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <http://www.gnu.org/licenses/>.
 */

package com.morlunk.mumbleclient.channel;

import android.app.Activity;
import android.app.AlertDialog;
import android.app.SearchManager;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.IntentFilter;
import android.database.CursorWrapper;
import android.graphics.PorterDuff;
import android.media.AudioManager;
import android.os.Bundle;
import android.os.RemoteException;
import android.support.v4.view.MenuItemCompat;
import android.support.v7.app.ActionBarActivity;
import android.support.v7.view.ActionMode;
import android.support.v7.widget.SearchView;
import android.view.LayoutInflater;
import android.view.Menu;
import android.view.MenuInflater;
import android.view.MenuItem;
import android.view.View;
import android.view.ViewGroup;
import android.widget.AdapterView;

import com.morlunk.jumble.IJumbleObserver;
import com.morlunk.jumble.IJumbleService;
import com.morlunk.jumble.model.Channel;
import com.morlunk.jumble.model.User;
import com.morlunk.jumble.util.JumbleObserver;
import com.morlunk.mumbleclient.R;
import com.morlunk.mumbleclient.channel.actionmode.ChannelActionModeCallback;
import com.morlunk.mumbleclient.channel.actionmode.UserActionModeCallback;
import com.morlunk.mumbleclient.db.DatabaseProvider;
import com.morlunk.mumbleclient.util.JumbleServiceFragment;
import com.morlunk.mumbleclient.view.PlumbleNestedListView;
import com.morlunk.mumbleclient.view.PlumbleNestedListView.OnNestedChildClickListener;
import com.morlunk.mumbleclient.view.PlumbleNestedListView.OnNestedGroupClickListener;

public class ChannelListFragment extends JumbleServiceFragment implements OnNestedChildClickListener, OnNestedGroupClickListener, UserActionModeCallback.LocalUserUpdateListener {

	private IJumbleObserver mServiceObserver = new JumbleObserver() {
        @Override
        public void onDisconnected() throws RemoteException {
            mChannelView.setAdapter(null);
        }

        @Override
        public void onUserJoinedChannel(User user, Channel newChannel, Channel oldChannel) throws RemoteException {
            updateChannelList();
            if(getService().getSession() == user.getSession()) {
                scrollToChannel(newChannel.getId());
            }
        }

        @Override
		public void onChannelAdded(Channel channel) throws RemoteException {
			updateChannelList();
		}

		@Override
		public void onChannelRemoved(Channel channel) throws RemoteException {
			updateChannelList();
		}

        @Override
        public void onChannelStateUpdated(Channel channel) throws RemoteException {
            updateChannel(channel);
        }

        @Override
        public void onUserConnected(User user) throws RemoteException {
            updateChannelList();
        }

        @Override
        public void onUserRemoved(User user, String reason) throws RemoteException {
            removeUser(user);
        }

        @Override
        public void onUserStateUpdated(User user) throws RemoteException {
            updateUser(user);
        }

        @Override
        public void onUserTalkStateUpdated(User user) throws RemoteException {
            updateUserTalking(user);
        }
	};

    private BroadcastReceiver mBluetoothReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            if(getActivity() != null)
                getActivity().supportInvalidateOptionsMenu(); // Update bluetooth menu item
        }
    };

	private PlumbleNestedListView mChannelView;
	private ChannelListAdapter mChannelListAdapter;
    private ChatTargetProvider mTargetProvider;
    private DatabaseProvider mDatabaseProvider;
    private ActionMode mActionMode;

    @Override
    public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setHasOptionsMenu(true);
    }

    @Override
    public void onAttach(Activity activity) {
        super.onAttach(activity);
        try {
            mTargetProvider = (ChatTargetProvider) getParentFragment();
        } catch (ClassCastException e) {
            throw new ClassCastException(getParentFragment().toString()+" must implement ChatTargetProvider");
        }
        try {
            mDatabaseProvider = (DatabaseProvider) getActivity();
        } catch (ClassCastException e) {
            throw new ClassCastException(getActivity().toString()+" must implement DatabaseProvider");
        }
    }

    @Override
    public View onCreateView(LayoutInflater inflater, ViewGroup container,
                             Bundle savedInstanceState) {
        View view = inflater.inflate(R.layout.fragment_channel_list, container, false);

        // Get the UI views
        mChannelView = (PlumbleNestedListView) view.findViewById(R.id.channelUsers);
        mChannelView.setOnChildClickListener(this);
        mChannelView.setOnGroupClickListener(this);

        return view;
    }

    @Override
    public void onActivityCreated(Bundle savedInstanceState) {
        super.onActivityCreated(savedInstanceState);
        registerForContextMenu(mChannelView);
        getActivity().registerReceiver(mBluetoothReceiver, new IntentFilter(AudioManager.ACTION_SCO_AUDIO_STATE_CHANGED));
    }

    @Override
    public void onDetach() {
        getActivity().unregisterReceiver(mBluetoothReceiver);
        super.onDetach();
    }

    @Override
    public IJumbleObserver getServiceObserver() {
        return mServiceObserver;
    }

    @Override
    public void onServiceBound(IJumbleService service) {
        try {
            if(mChannelListAdapter == null)
                setupChannelList();
            else
                updateChannelList();
        } catch (RemoteException e) {
            e.printStackTrace();
        }
    }

    @Override
    public void onPrepareOptionsMenu(Menu menu) {
        super.onPrepareOptionsMenu(menu);

        MenuItem muteItem = menu.findItem(R.id.menu_mute_button);
        MenuItem deafenItem = menu.findItem(R.id.menu_deafen_button);

        try {
            if(getService() != null && getService().isConnected() && getService().getSessionUser() != null) {
                // Color the action bar icons to the primary text color of the theme, TODO move this elsewhere
                int foregroundColor = getActivity().getTheme().obtainStyledAttributes(new int[]{android.R.attr.textColorPrimaryInverse}).getColor(0, -1);

                User self = getService().getSessionUser();
                muteItem.setIcon(self.isSelfMuted() ? R.drawable.ic_action_microphone_muted : R.drawable.ic_action_microphone);
                deafenItem.setIcon(self.isSelfDeafened() ? R.drawable.ic_action_audio_muted : R.drawable.ic_action_audio);
                muteItem.getIcon().mutate().setColorFilter(foregroundColor, PorterDuff.Mode.MULTIPLY);
                deafenItem.getIcon().mutate().setColorFilter(foregroundColor, PorterDuff.Mode.MULTIPLY);
            }
        } catch (RemoteException e) {
            e.printStackTrace();
        }

        try {
            if(getService() != null) {
                MenuItem bluetoothItem = menu.findItem(R.id.menu_bluetooth);
                bluetoothItem.setChecked(getService().isBluetoothAvailable());
            }
        } catch (RemoteException e) {
            e.printStackTrace();
        }
    }

    @Override
    public void onCreateOptionsMenu(Menu menu, MenuInflater inflater) {
        inflater.inflate(R.menu.fragment_channel_list, menu);

        MenuItem searchItem = menu.findItem(R.id.menu_search);
        SearchManager searchManager = (SearchManager) getActivity().getSystemService(Context.SEARCH_SERVICE);

        final SearchView searchView = (SearchView)MenuItemCompat.getActionView(searchItem);
        searchView.setSearchableInfo(searchManager.getSearchableInfo(getActivity().getComponentName()));
        searchView.setOnSuggestionListener(new SearchView.OnSuggestionListener() {
            @Override
            public boolean onSuggestionSelect(int i) {
                return false;
            }

            @Override
            public boolean onSuggestionClick(int i) {
                CursorWrapper cursor = (CursorWrapper) searchView.getSuggestionsAdapter().getItem(i);
                int typeColumn = cursor.getColumnIndex(SearchManager.SUGGEST_COLUMN_INTENT_EXTRA_DATA);
                int dataIdColumn = cursor.getColumnIndex(SearchManager.SUGGEST_COLUMN_INTENT_DATA);
                String itemType = cursor.getString(typeColumn);
                int itemId = cursor.getInt(dataIdColumn);
                if(ChannelSearchProvider.INTENT_DATA_CHANNEL.equals(itemType)) {
                    try {
                        if(getService().getSessionChannel().getId() != itemId) {
                            getService().joinChannel(itemId);
                        } else {
                            scrollToChannel(itemId);
                        }
                    } catch (RemoteException e) {
                        e.printStackTrace();
                    }
                    return true;
                } else if(ChannelSearchProvider.INTENT_DATA_USER.equals(itemType)) {
                    scrollToUser(itemId);
                    return true;
                }
                return false;
            }
        });
    }

    @Override
    public boolean onOptionsItemSelected(MenuItem item) {

        switch (item.getItemId()) {
            case R.id.menu_mute_button:
                try {
                    User self = getService().getSessionUser();

                    boolean muted = !self.isSelfMuted();
                    boolean deafened = self.isSelfDeafened();
                    deafened &= muted; // Undeafen if mute is off
                    self.setSelfMuted(muted);
                    self.setSelfDeafened(deafened);
                    getService().setSelfMuteDeafState(self.isSelfMuted(), self.isSelfDeafened());

                    getActivity().supportInvalidateOptionsMenu();
                } catch (RemoteException e) {
                    e.printStackTrace();
                }
                return true;
            case R.id.menu_deafen_button:
                try {
                    User self = getService().getSessionUser();

                    boolean deafened = self.isSelfDeafened();
                    self.setSelfDeafened(!deafened);
                    self.setSelfMuted(!deafened);
                    getService().setSelfMuteDeafState(self.isSelfDeafened(), self.isSelfDeafened());

                    getActivity().supportInvalidateOptionsMenu();
                } catch (RemoteException e) {
                    e.printStackTrace();
                }
                return true;
            case R.id.menu_search:
                return false;
            case R.id.menu_bluetooth:
                item.setChecked(!item.isChecked());
                try {
                    getService().setBluetoothEnabled(item.isChecked());
                } catch (RemoteException e) {
                    e.printStackTrace();
                }
                return true;
        }

        return super.onOptionsItemSelected(item);
    }

    private void setupChannelList() throws RemoteException {
        mChannelListAdapter = new ChannelListAdapter(getActivity(), mChannelView, getService(), mDatabaseProvider.getDatabase(), isShowingPinnedChannels());
        mChannelView.setAdapter(mChannelListAdapter);
		updateChannelList();
	}

	public void updateChannelList() throws RemoteException {
		mChannelListAdapter.updateChannelList();
		mChannelListAdapter.notifyDataSetChanged();
	}

	public void updateUser(User user) throws RemoteException {
		mChannelListAdapter.refreshUser(user);
	}

	public void updateChannel(Channel channel) throws RemoteException {
		if(channel.getDescription() != null || channel.getDescriptionHash() != null) {
//          TODO reimplement comment caching
//			mChannelListAdapter.commentsSeen.put(channel, mChannelListAdapter.database.isCommentSeen(
//				channel.getName(),
//				channel.getDescriptionHash() != null ? new String(channel.getDescriptionHash()) : channel.getDescription()));
		}
		updateChannelList();
	}

	public void updateUserTalking(User user) {
		mChannelListAdapter.refreshTalkingState(user);
	}

	/**
	 * Removes the user from the channel list.
	 *
	 * @param user
	 */
	public void removeUser(User user) {
        mChannelListAdapter.notifyDataSetChanged();
	}

	/**
	 * Scrolls to the passed channel.
	 */
	public void scrollToChannel(int channelId) {
		int channelPosition = mChannelListAdapter.getVisibleFlatGroupPosition(channelId);
        mChannelView.smoothScrollToPosition(channelPosition);
    }
	/**
	 * Scrolls to the passed user.
	 */
	public void scrollToUser(int userId) {
		int userPosition = mChannelListAdapter.getVisibleFlatChildPosition(userId);
		mChannelView.smoothScrollToPosition(userPosition);
	}

    public void setChatTarget(User chatTarget) {
		User oldTarget = chatTarget;
		if (mChannelListAdapter != null) {
            try {
                if (oldTarget != null) mChannelListAdapter.refreshUser(oldTarget);
                mChannelListAdapter.refreshUser(chatTarget);
            } catch (RemoteException e) {
                e.printStackTrace();
            }
		}
	}

    private boolean isShowingPinnedChannels() {
        return getArguments().getBoolean("pinned");
    }

	@Override
	public void onNestedChildClick(AdapterView<?> parent, View view, int groupId, int childPosition) {
        User user = mChannelListAdapter.getChild(groupId, childPosition);
        if (user == null) return;
        if (mTargetProvider.getChatTarget() != null &&
                user.equals(mTargetProvider.getChatTarget().getUser()) &&
                mActionMode != null) {
            // Dismiss action mode if double pressed. FIXME: use list view selection instead?
            mActionMode.finish();
        } else {
            ActionMode.Callback cb = new UserActionModeCallback(getActivity(), getService(), user, mTargetProvider, getChildFragmentManager(), this) {
                @Override
                public void onDestroyActionMode(ActionMode actionMode) {
                    super.onDestroyActionMode(actionMode);
                    mActionMode = null;
                }
            };
            mActionMode = ((ActionBarActivity)getActivity()).startSupportActionMode(cb);
        }
	}

	@Override
	public void onNestedGroupClick(AdapterView<?> parent, View view, int groupId) {
        Channel channel = mChannelListAdapter.getGroup(groupId);
        if (channel == null) return;
        if (mTargetProvider.getChatTarget() != null &&
                channel.equals(mTargetProvider.getChatTarget().getChannel()) &&
                mActionMode != null) {
            // Dismiss action mode if double pressed. FIXME: use list view selection instead?
            mActionMode.finish();
        } else {
            ActionMode.Callback cb = new ChannelActionModeCallback(getActivity(),
                    getService(), channel, mTargetProvider, mDatabaseProvider.getDatabase(),
                    getChildFragmentManager()) {
                @Override
                public void onDestroyActionMode(ActionMode actionMode) {
                    super.onDestroyActionMode(actionMode);
                    mActionMode = null;
                }
            };
            mActionMode = ((ActionBarActivity)getActivity()).startSupportActionMode(cb);
        }
    }

    @Override
    public void onLocalUserStateUpdated(User user) {
        try {
            updateUser(user);
        } catch (RemoteException e) {
            e.printStackTrace();
        }
    }
}
