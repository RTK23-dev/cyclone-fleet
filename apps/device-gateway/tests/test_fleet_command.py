from __future__ import annotations

import unittest

from cyclone_device_gateway.desktop_runtime.fleet_command import KnownDevice, build_directory, split_command


def fleet(*rows):
    """rows: (deviceId, name/model, paired)"""
    return [{"deviceId": i, "name": n, "model": n, "paired": p} for i, n, p in rows]


def two_phones():
    return build_directory(fleet(("dev_a", "CPH2717", True), ("dev_b", "SM-X710", True)),
                           {"dev_a": "Work Phone", "dev_b": "Tablet"})


def three_phones():
    return build_directory(
        fleet(("dev_a", "Pixel 8", True), ("dev_b", "SM-S918", True), ("dev_c", "SM-X710", True)),
        {"dev_a": "Pixel Main", "dev_b": "Samsung Work", "dev_c": "Tablet"})


def by_label(plan):
    return {a.label: a.goal for a in plan.assignments}


class NamedSplitTests(unittest.TestCase):
    def test_your_original_example_suffix_style(self):
        plan = split_command("unlock and check messages on Work Phone, open camera on Tablet", two_phones())
        self.assertTrue(plan.ok, plan.clarification)
        self.assertEqual(by_label(plan), {"Work Phone": "Unlock and check messages", "Tablet": "Open camera"})
        self.assertEqual(plan.confidence, "high")
        self.assertFalse(plan.needs_confirmation)

    def test_internal_and_stays_inside_the_task(self):
        plan = split_command("open camera and take a photo on Tablet, check mail on Work Phone", two_phones())
        self.assertEqual(by_label(plan), {"Tablet": "Open camera and take a photo", "Work Phone": "Check mail"})

    def test_prefix_style_with_sentences(self):
        plan = split_command("On Work Phone open Gmail and summarize unread email. On Tablet open YouTube.", two_phones())
        self.assertEqual(by_label(plan), {"Work Phone": "Open Gmail and summarize unread email", "Tablet": "Open YouTube"})
        self.assertEqual(plan.confidence, "high")

    def test_briefs_three_phone_example(self):
        text = ("On Pixel Main, open WhatsApp and find the latest message from John. "
                "On Samsung Work, open Gmail and summarize unread work emails. On the tablet, open YouTube.")
        plan = split_command(text, three_phones())
        self.assertTrue(plan.ok, plan.clarification)
        self.assertEqual(by_label(plan), {
            "Pixel Main": "Open WhatsApp and find the latest message from John",
            "Samsung Work": "Open Gmail and summarize unread work emails",
            "Tablet": "Open YouTube",
        })

    def test_tell_x_to_style(self):
        plan = split_command("Tell Work Phone to open Gmail and tell Tablet to open YouTube", two_phones())
        self.assertEqual(by_label(plan), {"Work Phone": "Open Gmail", "Tablet": "Open YouTube"})

    def test_colon_style(self):
        plan = split_command("Work Phone: open Gmail; Tablet: open YouTube", two_phones())
        self.assertEqual(by_label(plan), {"Work Phone": "Open Gmail", "Tablet": "Open YouTube"})

    def test_only_and_between_clauses(self):
        plan = split_command("open Gmail on Work Phone and open YouTube on Tablet", two_phones())
        self.assertEqual(by_label(plan), {"Work Phone": "Open Gmail", "Tablet": "Open YouTube"})

    def test_case_insensitive_and_article(self):
        plan = split_command("open youtube on the TABLET, check mail on my work phone", two_phones())
        self.assertEqual(by_label(plan), {"Tablet": "Open youtube", "Work Phone": "Check mail"})

    def test_single_named_phone(self):
        plan = split_command("open Instagram on Work Phone", two_phones())
        self.assertEqual(by_label(plan), {"Work Phone": "Open Instagram"})

    def test_curly_apostrophe_in_a_phone_name(self):
        directory = build_directory(fleet(("dev_a", "CPH2717", True)), {"dev_a": "RTK's OnePlus"})
        plan = split_command("open Discord on RTK\u2019s OnePlus", directory)
        self.assertEqual(by_label(plan), {"RTK's OnePlus": "Open Discord"})

    def test_longest_name_wins(self):
        directory = build_directory(fleet(("dev_a", "x", True), ("dev_b", "y", True)),
                                    {"dev_a": "Tablet", "dev_b": "Tablet Pro"})
        plan = split_command("open Maps on Tablet Pro", directory)
        self.assertEqual(by_label(plan), {"Tablet Pro": "Open Maps"})

    def test_a_name_inside_another_word_does_not_match(self):
        directory = build_directory(fleet(("dev_a", "x", True), ("dev_b", "y", True)),
                                    {"dev_a": "Phone Two", "dev_b": "Tablet"})
        plan = split_command("play music on Tablet with my headphone two adapter", directory)
        self.assertEqual(list(by_label(plan)), ["Tablet"])

    def test_same_phone_twice_is_merged_in_order(self):
        plan = split_command("open Gmail on Work Phone, open YouTube on Tablet, search invoices on Work Phone", two_phones())
        goals = by_label(plan)
        self.assertEqual(goals["Tablet"], "Open YouTube")
        self.assertEqual(goals["Work Phone"], "Open Gmail. Then search invoices")
        self.assertTrue(any("more than once" in n for n in plan.notes))


class SharedInstructionAndQuoteTests(unittest.TestCase):
    def test_one_instruction_for_a_list_of_phones(self):
        plan = split_command("check the battery on Work Phone and Tablet", two_phones())
        self.assertEqual(by_label(plan), {"Work Phone": "Check the battery", "Tablet": "Check the battery"})
        self.assertEqual(plan.confidence, "high")
        self.assertFalse(plan.needs_confirmation)

    def test_list_in_either_order_and_with_repeated_prepositions(self):
        self.assertEqual(by_label(split_command("open YouTube on Tablet and Work Phone", two_phones())),
                         {"Tablet": "Open YouTube", "Work Phone": "Open YouTube"})
        self.assertEqual(by_label(split_command("on Work Phone and on Tablet open Settings", two_phones())),
                         {"Work Phone": "Open Settings", "Tablet": "Open Settings"})

    def test_should_both_is_not_part_of_the_goal(self):
        plan = split_command("Work Phone and Tablet should both open Maps", two_phones())
        self.assertEqual(by_label(plan), {"Work Phone": "Open Maps", "Tablet": "Open Maps"})

    def test_a_list_and_a_separate_instruction_together(self):
        plan = split_command("open Maps on Work Phone and Tablet, open Gmail on Old Phone",
                             build_directory(fleet(("dev_a", "a", True), ("dev_b", "b", True), ("dev_c", "c", True)),
                                             {"dev_a": "Work Phone", "dev_b": "Tablet", "dev_c": "Old Phone"}))
        self.assertEqual(by_label(plan), {"Work Phone": "Open Maps", "Tablet": "Open Maps", "Old Phone": "Open Gmail"})

    def test_a_phone_name_inside_a_quoted_message_is_content_not_a_target(self):
        plan = split_command("send 'see you on Tablet day' to John on Work Phone", two_phones())
        self.assertEqual(list(by_label(plan)), ["Work Phone"])
        self.assertIn("see you on Tablet day", plan.assignments[0].goal)

    def test_separators_inside_quotes_do_not_split(self):
        plan = split_command('search "cats, and dogs on Tablet" on Work Phone, open Maps on Tablet', two_phones())
        self.assertEqual(by_label(plan), {"Work Phone": 'Search "cats, and dogs on Tablet"', "Tablet": "Open Maps"})

    def test_apostrophe_in_a_phone_name_is_not_a_quote(self):
        directory = build_directory(fleet(("dev_a", "x", True), ("dev_b", "y", True)),
                                    {"dev_a": "RTK's OnePlus", "dev_b": "Tablet"})
        plan = split_command("open Discord on RTK's OnePlus, open Maps on Tablet", directory)
        self.assertEqual(by_label(plan), {"RTK's OnePlus": "Open Discord", "Tablet": "Open Maps"})


class NeverGuessTests(unittest.TestCase):
    def test_two_phones_share_a_name(self):
        directory = build_directory(fleet(("dev_a", "CPH2717", True), ("dev_b", "CPH2717", True)), {})
        plan = split_command("open Gmail on CPH2717", directory)
        self.assertFalse(plan.ok)
        self.assertIn("could be", plan.clarification)
        self.assertEqual(plan.assignments, [])

    def test_no_name_and_several_phones_asks(self):
        plan = split_command("check my email", two_phones())
        self.assertFalse(plan.ok)
        self.assertIn("Which phone", plan.clarification)
        self.assertIn("Work Phone", plan.clarification)

    def test_no_name_and_one_phone_uses_it(self):
        directory = build_directory(fleet(("dev_a", "CPH2717", True)), {"dev_a": "Work Phone"})
        plan = split_command("check my email", directory)
        self.assertEqual(by_label(plan), {"Work Phone": "Check my email"})
        self.assertEqual(plan.kind, "single")

    def test_unpaired_phones_are_not_defaulted_to(self):
        directory = build_directory(fleet(("dev_a", "A", False), ("dev_b", "B", False)), {})
        plan = split_command("check my email", directory)
        self.assertFalse(plan.ok)

    def test_a_name_with_no_instruction_asks(self):
        plan = split_command("open Gmail on Work Phone, on Tablet", two_phones())
        self.assertFalse(plan.ok)
        self.assertIn("Tablet", plan.clarification)

    def test_cannot_find_the_boundary(self):
        plan = split_command("Work Phone Tablet", two_phones())
        self.assertFalse(plan.ok)

    def test_empty_and_no_phones(self):
        self.assertFalse(split_command("   ", two_phones()).ok)
        self.assertFalse(split_command("do a thing", []).ok)

    def test_a_remembered_but_disconnected_phone_is_still_recognised(self):
        # Old Phone was nicknamed once, isn't connected now. Naming it must not re-route its job to Tablet.
        directory = build_directory(fleet(("dev_b", "SM-X710", True)), {"dev_b": "Tablet", "dev_old": "Old Phone"})
        plan = split_command("check mail on Old Phone, open camera on Tablet", directory)
        goals = by_label(plan)
        self.assertEqual(goals, {"Old Phone": "Check mail", "Tablet": "Open camera"})
        old = next(d for d in directory if d.device_id == "dev_old")
        self.assertFalse(old.paired)

    def test_generic_words_are_never_phone_names(self):
        directory = build_directory([{"deviceId": "dev_a", "name": "Android phone", "model": None, "paired": True},
                                     {"deviceId": "dev_b", "name": "Tablet", "model": None, "paired": True}], {})
        plan = split_command("open the phone app on Tablet", directory)
        self.assertEqual(by_label(plan), {"Tablet": "Open the phone app"})


class ConfirmationTests(unittest.TestCase):
    def test_broadcast_to_all_needs_confirmation(self):
        plan = split_command("open Settings on all phones", two_phones())
        self.assertEqual(plan.kind, "broadcast")
        self.assertEqual(len(plan.assignments), 2)
        self.assertTrue(plan.needs_confirmation)
        self.assertEqual({a.goal for a in plan.assignments}, {"Open Settings"})

    def test_broadcast_phrasings(self):
        for phrase in ("on every phone", "on both devices", "on all my phones", "across all online phones"):
            plan = split_command(f"turn on wifi {phrase}", two_phones())
            self.assertEqual(plan.kind, "broadcast", phrase)
            self.assertEqual({a.goal for a in plan.assignments}, {"Turn on wifi"}, phrase)

    def test_mixing_named_and_all_asks(self):
        plan = split_command("open Gmail on Work Phone and open Maps on all phones", two_phones())
        self.assertFalse(plan.ok)

    def test_bare_mention_lowers_confidence(self):
        plan = split_command("Work Phone check mail, Tablet open camera", two_phones())
        self.assertTrue(plan.ok)
        self.assertEqual(plan.confidence, "medium")
        self.assertTrue(plan.needs_confirmation)

    def test_ambiguous_separator_lowers_confidence(self):
        plan = split_command("open Gmail, search invoices, then open Maps on Work Phone and open YouTube on Tablet", two_phones())
        self.assertTrue(plan.ok)
        self.assertEqual(by_label(plan)["Tablet"], "Open YouTube")

    def test_then_is_flagged_as_parallel(self):
        plan = split_command("open Gmail on Work Phone, then open YouTube on Tablet", two_phones())
        self.assertTrue(plan.ok)
        self.assertEqual(by_label(plan), {"Work Phone": "Open Gmail", "Tablet": "Open YouTube"})
        self.assertTrue(any("same time" in n for n in plan.notes))

    def test_public_shape(self):
        plan = split_command("open Gmail on Work Phone", two_phones())
        data = plan.public()
        self.assertEqual(set(data), {"assignments", "clarification", "confidence", "needsConfirmation", "kind", "notes"})
        self.assertEqual(data["assignments"][0]["deviceId"], "dev_a")


if __name__ == "__main__":
    unittest.main()
