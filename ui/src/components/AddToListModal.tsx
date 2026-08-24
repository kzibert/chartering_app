import { useEffect, useState } from 'react';
import {
  App,
  Alert,
  Checkbox,
  Input,
  InputNumber,
  Modal,
  Radio,
  Space,
  Spin,
  Statistic,
  Typography,
} from 'antd';
import { useQuery } from '@tanstack/react-query';
import { useSavedLists, useListMutations, useCurrentList, contactToEntry } from '../circulations/store';
import { reportAdd } from '../circulations/addResult';
import {
  DEFAULT_KEYWORDS,
  DEFAULT_MAX_PER_COMPANY,
  narrowContacts,
  type NarrowOptions,
} from '../circulations/narrow';
import type { ContactResponse } from '../api/types';

/** A new list is chosen by picking this instead of an existing list id. */
const NEW_LIST = -1;

/**
 * The narrowing settings outlive the dialog: they describe how this desk circulates
 * rather than what is being added right now, and retyping them on every bulk add would
 * make the defaults the only thing anyone ever uses.
 */
const NARROW_KEY = 'chartering.addToList.narrowing.v2';

function loadNarrowing(): NarrowOptions {
  const fallback: NarrowOptions = {
    maxPerCompany: DEFAULT_MAX_PER_COMPANY,
    useKeywords: false,
    keywords: DEFAULT_KEYWORDS,
  };
  try {
    const raw = localStorage.getItem(NARROW_KEY);
    if (!raw) return fallback;
    const saved = JSON.parse(raw) as Partial<NarrowOptions>;
    return {
      // null is a real, meaningful value here — "no cap" — so it is only replaced when
      // the key is missing outright rather than whenever it is falsy.
      maxPerCompany:
        saved.maxPerCompany === null || typeof saved.maxPerCompany === 'number'
          ? saved.maxPerCompany
          : fallback.maxPerCompany,
      useKeywords: saved.useKeywords ?? fallback.useKeywords,
      keywords: saved.keywords ?? fallback.keywords,
    };
  } catch {
    return fallback; // a corrupt or unreadable entry is not worth failing the dialog over
  }
}

export interface AddToListSource {
  /** What is being added, for the dialog's title: "3 selected companies". */
  label: string;
  /**
   * Fetches the addresses to add. Called with the confirmed-only choice so flipping the
   * checkbox re-counts without the caller re-implementing the query.
   */
  collect: (confirmedOnly: boolean) => Promise<ContactResponse[]>;
  /** Distinguishes one preview query from another in the react-query cache. */
  cacheKey: unknown[];
  /**
   * Set by the People tab, where a ticked row is a person rather than a company. The cap
   * is still per company there, so it can drop people the user picked by hand — which is
   * worth saying out loud rather than letting the count quietly come up short.
   */
  rowsArePeople?: boolean;
}

/**
 * Adds a set of contacts to a circulation list.
 *
 * <p>The dialog previews before it writes: which addresses come back is decided by the
 * circ/main flags and the narrowing below rather than by anything typed into the list
 * name, so showing the resulting count first is the only way "add all matching" is an
 * informed click. The preview query is the same call the confirm makes, and the same
 * narrowing is applied to both, so what is counted is what is added.
 */
export default function AddToListModal({
  open,
  onClose,
  onAdded,
  source,
}: {
  open: boolean;
  onClose: () => void;
  /** Fires only after addresses were actually written — never on cancel. */
  onAdded?: () => void;
  source: AddToListSource | null;
}) {
  const { message } = App.useApp();
  const savedLists = useSavedLists();
  const current = useCurrentList();
  const { addTo, create } = useListMutations();

  const [target, setTarget] = useState<number | null>(null);
  const [newName, setNewName] = useState('');
  const [confirmedOnly, setConfirmedOnly] = useState(false);
  const [narrowing, setNarrowing] = useState<NarrowOptions>(loadNarrowing);

  const setNarrow = (patch: Partial<NarrowOptions>) =>
    setNarrowing((prev) => {
      const next = { ...prev, ...patch };
      try {
        localStorage.setItem(NARROW_KEY, JSON.stringify(next));
      } catch {
        /* a full or blocked localStorage must not stop the add */
      }
      return next;
    });

  // Default to the current list every time the dialog opens: it is the common case, and a
  // remembered target from a previous session is exactly the wrong thing to write into.
  // The narrowing is deliberately *not* reset — it is a preference, not a selection.
  useEffect(() => {
    if (open) {
      setTarget(current.listId ?? null);
      setNewName('');
    }
  }, [open, current.listId]);

  const preview = useQuery({
    queryKey: ['add-to-list-preview', source?.cacheKey, confirmedOnly],
    queryFn: () => source!.collect(confirmedOnly),
    enabled: open && source != null,
  });

  const collected = preview.data ?? [];
  const { kept: contacts, peopleDropped, peopleBefore } = narrowContacts(collected, narrowing);
  const creatingNew = target === NEW_LIST;
  const canConfirm =
    contacts.length > 0 && (creatingNew ? newName.trim().length > 0 : target != null);

  const confirm = async () => {
    if (!canConfirm) return;
    const entries = contacts.map(contactToEntry);
    try {
      const listId = creatingNew ? (await create.mutateAsync({ name: newName.trim() })).id : target!;
      const result = await addTo.mutateAsync({ listId, entries });
      const name = creatingNew
        ? newName.trim()
        : (savedLists.data?.find((l) => l.id === listId)?.name ?? 'the current list');
      reportAdd(message, result, name);
      onAdded?.();
      onClose();
    } catch {
      /* the axios interceptor surfaces the error */
    }
  };

  // The number of distinct mailboxes is what matters, not the number of contact rows: two
  // people sharing a desk address is one message, and the list dedupes it away on write.
  const distinct = new Set(contacts.map((c) => c.contactValue.trim().toLowerCase())).size;
  // Counted in addresses rather than contact rows, so two rows sharing one address do not
  // read as something the limit held back — nothing was lost when they collapsed.
  const collectedDistinct = new Set(collected.map((c) => c.contactValue.trim().toLowerCase())).size;
  const removedByCap = collectedDistinct - distinct;
  const capped = narrowing.maxPerCompany != null;

  return (
    <Modal
      open={open}
      onCancel={onClose}
      onOk={confirm}
      okText={contacts.length ? `Add ${distinct}` : 'Add'}
      okButtonProps={{ disabled: !canConfirm, loading: addTo.isPending || create.isPending }}
      title={source ? `Add ${source.label} to a circulation list` : 'Add to a circulation list'}
      width={520}
    >
      <Space direction="vertical" size="middle" style={{ width: '100%' }}>
        <div>
          <Typography.Text strong>Add to</Typography.Text>
          <Radio.Group
            style={{ display: 'block', marginTop: 8 }}
            value={target}
            onChange={(e) => setTarget(e.target.value)}
          >
            <Space direction="vertical" size={4}>
              <Radio value={current.listId ?? null} disabled={current.listId == null}>
                Current list{' '}
                <Typography.Text type="secondary">
                  ({current.entries.length} address{current.entries.length === 1 ? '' : 'es'})
                </Typography.Text>
              </Radio>
              {(savedLists.data ?? []).map((l) => (
                <Radio key={l.id} value={l.id}>
                  {l.name}{' '}
                  <Typography.Text type="secondary">
                    ({l.entryCount} address{l.entryCount === 1 ? '' : 'es'})
                  </Typography.Text>
                </Radio>
              ))}
              <Radio value={NEW_LIST}>New list…</Radio>
            </Space>
          </Radio.Group>
          {creatingNew && (
            <Input
              autoFocus
              style={{ marginTop: 8 }}
              placeholder="Name for the new list, e.g. Handysize owners — Med"
              value={newName}
              onChange={(e) => setNewName(e.target.value)}
              onPressEnter={confirm}
              maxLength={150}
            />
          )}
        </div>

        <Checkbox checked={confirmedOnly} onChange={(e) => setConfirmedOnly(e.target.checked)}>
          Confirmed contacts only
        </Checkbox>

        <div>
          <Space size="small" align="center">
            <Typography.Text>Max emails per company</Typography.Text>
            <InputNumber
              min={1}
              max={99}
              style={{ width: 80 }}
              value={narrowing.maxPerCompany}
              placeholder="all"
              onChange={(v) => setNarrow({ maxPerCompany: v == null ? null : Number(v) })}
            />
          </Space>
          <div>
            <Typography.Text type="secondary" style={{ fontSize: 12 }}>
              {capped
                ? 'Clear the field to add every address a company offers.'
                : 'No limit — every address a company offers is added.'}
            </Typography.Text>
          </div>
        </div>

        <div>
          <Checkbox
            checked={narrowing.useKeywords}
            onChange={(e) => setNarrow({ useKeywords: e.target.checked })}
          >
            Use keywords
          </Checkbox>
          <Input
            style={{ marginTop: 8 }}
            disabled={!narrowing.useKeywords}
            value={narrowing.keywords}
            onChange={(e) => setNarrow({ keywords: e.target.value })}
            placeholder="chartering, chart"
            maxLength={200}
          />
          {narrowing.useKeywords && !capped && (
            <Typography.Text type="warning" style={{ fontSize: 12 }}>
              With no limit per company every address is added anyway, so the keywords have
              nothing to choose between. Set a limit for them to take effect.
            </Typography.Text>
          )}
        </div>

        {preview.isLoading ? (
          <Spin />
        ) : contacts.length === 0 ? (
          <Alert
            type="info"
            showIcon
            message="No addresses to add"
            description={
              confirmedOnly
                ? 'Nothing here has a confirmed, working email address. Try again without the confirmed-only filter.'
                : 'Nothing here has a working email address on file.'
            }
          />
        ) : (
          <Space direction="vertical" size="small" style={{ width: '100%' }}>
            {/* Three four-figure counts and their captions just fit a phone; a fifth digit
                would not, so they are allowed to fall onto a second line. */}
            <Space size="large" wrap>
              <Statistic title="Addresses" value={distinct} />
              <Statistic
                title="Sources"
                value={new Set(contacts.map((c) => c.personId ?? `c${c.companyId}`)).size}
              />
              {removedByCap > 0 && (
                <Statistic
                  title="Held back by the limit"
                  value={removedByCap}
                  valueStyle={{ fontSize: 20 }}
                />
              )}
            </Space>
            {source?.rowsArePeople && peopleDropped > 0 && (
              <Alert
                type="warning"
                showIcon
                message={`${peopleBefore} people → ${distinct} address${distinct === 1 ? '' : 'es'}`}
                description={`The limit is per company, so ${peopleDropped} of the people here contribute nothing. Raise "max emails per company", or clear it, to reach all of them.`}
              />
            )}
          </Space>
        )}

        <Typography.Text type="secondary" style={{ fontSize: 12 }}>
          Addresses are chosen by the contact flags: the ones flagged <b>circ</b> where a person
          has any, otherwise their <b>main</b> address, otherwise all their working addresses.
          Dead addresses are never collected. The limit above then keeps the best few per
          company — circ first, then main, then the rest — with the keywords, when ticked,
          deciding between equally ranked addresses by matching the address itself.
        </Typography.Text>
      </Space>
    </Modal>
  );
}
