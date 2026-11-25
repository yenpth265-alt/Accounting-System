package vn.ketoan.application.service;

import java.util.List;

import vn.ketoan.application.port.PostingPolicy;

/** Composition point for the standard posting policies. */
final class PostingPolicies {

    private PostingPolicies() {
    }

    static List<PostingPolicy> standard() {
        return List.of(new CorrectionPostingPolicy(), new PrepaidPostingPolicy());
    }
}
