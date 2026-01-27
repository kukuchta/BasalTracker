package com.kukuchta.basaltracker.ui.list;

import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;
import androidx.fragment.app.FragmentTransaction;
import androidx.lifecycle.ViewModelProvider;
import androidx.recyclerview.widget.LinearLayoutManager;

import com.google.android.material.floatingactionbutton.ExtendedFloatingActionButton;
import com.kukuchta.basaltracker.R;
import com.kukuchta.basaltracker.domain.BasalProfile;
import com.kukuchta.basaltracker.ui.editor.ProfileCombinedEditorFragment;
import com.kukuchta.basaltracker.viewmodel.ProfileViewModel;
import com.google.android.material.snackbar.Snackbar;

import java.util.List;

public class ProfileListFragment extends Fragment {

    private ProfileViewModel viewModel;
    private androidx.recyclerview.widget.RecyclerView rvProfiles;
    private ExtendedFloatingActionButton fabAdd;
    private ProfilesListAdapter adapter;

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater,
                             @Nullable ViewGroup container,
                             @Nullable Bundle savedInstanceState) {
        return inflater.inflate(R.layout.fragment_profile_list, container, false);
    }

    @Override
    public void onViewCreated(@NonNull View v, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(v, savedInstanceState);

        viewModel = new ViewModelProvider(requireActivity()).get(ProfileViewModel.class);

        rvProfiles = v.findViewById(R.id.rvProfiles);
        fabAdd = v.findViewById(R.id.fabAdd);

        rvProfiles.setLayoutManager(new LinearLayoutManager(requireContext()));
        adapter = new ProfilesListAdapter(new ProfilesListAdapter.ActionListener() {
            @Override
            public void onEdit(BasalProfile profile) {
                openEditor(profile.getId());
            }

            @Override
            public void onDuplicate(BasalProfile profile) {
                viewModel.duplicateProfile(profile.getId(), " (kopia)", newProfileId -> {});
            }


            @Override
            public void onDelete(BasalProfile profile) {
                viewModel.deleteProfile(profile.getId(), () ->
                        Snackbar.make(rvProfiles, "Profil usunięty.", Snackbar.LENGTH_SHORT).show());
            }
        });
        rvProfiles.setAdapter(adapter);

        // Load list data
        viewModel.getProfiles().observe(getViewLifecycleOwner(), this::bindProfiles);
        viewModel.loadAllProfiles();

        fabAdd.setOnClickListener(x -> {
            // Create empty profile and open editor with the returned ID
            viewModel.createEmptyProfile("Nowy profil", 0.05, this::openEditor);
        });
    }

    private void bindProfiles(List<BasalProfile> profiles) {
        adapter.submitList(profiles);
    }

    private void openEditor(long profileId) {
        ProfileCombinedEditorFragment editor = ProfileCombinedEditorFragment.newInstance(profileId);
        FragmentTransaction ft = getParentFragmentManager().beginTransaction();
        ft.replace(android.R.id.content, editor);
        ft.addToBackStack(null);
        ft.commit();
    }
}
