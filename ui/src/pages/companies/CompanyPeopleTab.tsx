import { useMemo, useState, type CSSProperties, type ReactNode } from 'react';
import {
  App,
  Button,
  Collapse,
  List,
  Popconfirm,
  Space,
  Spin,
  Tag,
  Tooltip,
  Typography,
} from 'antd';
import {
  DeleteOutlined,
  DownOutlined,
  EditOutlined,
  PlusOutlined,
  UpOutlined,
} from '@ant-design/icons';
import {
  DndContext,
  DragOverlay,
  KeyboardSensor,
  PointerSensor,
  useDraggable,
  useDroppable,
  useSensor,
  useSensors,
  type DragEndEvent,
  type DragStartEvent,
} from '@dnd-kit/core';
import { useCompanyContacts, useContactMutations, usePeople, usePersonMutations } from '../../api/hooks';
import ContactLine, { ContactDragHandle } from '../../components/ContactLine';
import { ContactRowExpansion } from '../../components/ContactRowExpansion';
import EditToolbar, { useEditMode } from '../../components/EditToolbar';
import GreetingName from '../../components/GreetingName';
import LeftCompanyButton from '../../components/LeftCompanyButton';
import { recordRecent } from '../../recent/store';
import type { ContactResponse, PersonResponse } from '../../api/types';

/**
 * People at the company, each with their contacts, plus the addresses that belong to the
 * company itself.
 *
 * Lives in its own file because it is now three things at once — a grouped list, a set of
 * drop targets, and the tab where contacts are refiled — and burying that inside the
 * drawer made the drawer hard to read.
 *
 * **Dragging.** A contact is filed under a person, or under nobody and only the company.
 * Changing that used to mean opening the edit form and clearing a select, which is a lot
 * of ceremony for "this number is actually Michael's". The groups on this tab are already
 * exactly the choices, so they are the drop targets: drop on a person to refile, drop on
 * "The company itself" to make the address company-wide.
 *
 * Only the two links move — the value, kind, notes, greeting and every flag stay as they
 * are (see the `assign` endpoint). The move saves immediately and offers an undo, because
 * the common case is tidying several contacts in a row and a confirm dialog on each would
 * make that tedious; the uncommon case is a mis-drop, which the undo answers.
 */
export default function CompanyPeopleTab({
  id,
  onAddPerson,
  onEditPerson,
  onAddContact,
  onEditContact,
}: {
  id: number;
  onAddPerson: () => void;
  onEditPerson: (p: PersonResponse) => void;
  onAddContact: (personId: number) => void;
  onEditContact: (ct: ContactResponse) => void;
}) {
  const { data: people, isLoading: loadingPeople } = usePeople(id);
  const { data: contacts, isLoading: loadingContacts } = useCompanyContacts(id);
  const { remove: removePerson } = usePersonMutations();
  const { remove: removeContact, assign } = useContactMutations();
  const [editMode, setEditMode] = useEditMode(id);
  const [openKeys, setOpenKeys] = useState<string[]>([]);
  const [dragging, setDragging] = useState<ContactResponse | null>(null);
  const { notification } = App.useApp();

  // A click must stay a click: the row itself opens the contact's controls, so the drag
  // only starts once the pointer has actually travelled.
  const sensors = useSensors(
    useSensor(PointerSensor, { activationConstraint: { distance: 6 } }),
    useSensor(KeyboardSensor),
  );

  const { byPerson, companyWide } = useMemo(() => {
    const grouped = new Map<number, ContactResponse[]>();
    // Addresses on the company and on nobody — chartering@, ops@, the switchboard. They
    // have no person to sit under, and dropping them here is how one goes missing: the row
    // is on the Contacts tab, but this is the tab somebody opens to ask "who do we know
    // there". It is also the drop target that makes an address company-wide.
    const loose: ContactResponse[] = [];
    (contacts ?? []).forEach((ct) => {
      if (ct.personId == null) {
        loose.push(ct);
        return;
      }
      grouped.set(ct.personId, [...(grouped.get(ct.personId) ?? []), ct]);
    });
    return { byPerson: grouped, companyWide: loose };
  }, [contacts]);

  const allKeys = (people ?? []).map((p) => String(p.id));
  const allOpen = allKeys.length > 0 && openKeys.length === allKeys.length;

  const move = (ct: ContactResponse, toPersonId: number | undefined) => {
    const from = ct.personId;
    if (from === toPersonId) return;
    const target = toPersonId == null
      ? 'the company itself'
      : people?.find((p) => p.id === toPersonId)?.fullName ?? 'that person';
    assign.mutate(
      { id: ct.id, personId: toPersonId, companyId: id },
      {
        onSuccess: () =>
          notification.success({
            message: `${ct.contactValue} moved to ${target}`,
            description:
              'Only who it is filed under changed — the value, notes, greeting and flags are untouched.',
            btn: (
              <Button
                size="small"
                onClick={() => assign.mutate({ id: ct.id, personId: from, companyId: id })}
              >
                Undo
              </Button>
            ),
            duration: 6,
          }),
      },
    );
  };

  const onDragEnd = ({ active, over }: DragEndEvent) => {
    setDragging(null);
    if (!over) return;
    const ct = active.data.current?.ct as ContactResponse | undefined;
    const to = over.data.current?.personId as number | undefined;
    if (ct) move(ct, to);
  };

  const onDragStart = ({ active }: DragStartEvent) =>
    setDragging((active.data.current?.ct as ContactResponse) ?? null);

  if (loadingPeople || loadingContacts) return <Spin />;

  return (
    <DndContext sensors={sensors} onDragStart={onDragStart} onDragEnd={onDragEnd}>
      <ContactRowExpansion>
        <EditToolbar
          editing={editMode}
          onToggle={setEditMode}
          extra={
            allKeys.length > 0 && (
              <Tooltip
                title={
                  allOpen
                    ? 'Collapse everyone again'
                    : 'Open every person at once, so all their contacts are on screen together'
                }
              >
                <Button
                  size="small"
                  icon={allOpen ? <UpOutlined /> : <DownOutlined />}
                  onClick={() => setOpenKeys(allOpen ? [] : allKeys)}
                >
                  {allOpen ? 'Collapse all' : 'Expand all'}
                </Button>
              </Tooltip>
            )
          }
        >
          <Button size="small" icon={<PlusOutlined />} onClick={onAddPerson}>
            Add person
          </Button>
        </EditToolbar>

        {/* Rendered whenever there is something in it, and throughout edit mode even when
            empty. Two reasons: the one target that makes an address company-wide must exist
            before an address already is, and dnd-kit measures droppables as a drag begins —
            a zone that first mounts mid-drag registers a frame late and can miss the drop it
            exists for. */}
        {(companyWide.length > 0 || editMode) && (
          <DropZone personId={undefined} active={dragging != null} style={{ marginBottom: 12 }}>
            <Space size={4} style={{ marginBottom: 4 }}>
              <strong>The company itself</strong>
              <Tooltip title="Addresses and numbers that belong to the company rather than to any one person — a chartering@ or ops@ desk. Circulars to them open with a general greeting unless the contact carries its own.">
                <Typography.Text type="secondary">
                  {companyWide.length} contact{companyWide.length === 1 ? '' : 's'}
                </Typography.Text>
              </Tooltip>
            </Space>
            {companyWide.length === 0 ? (
              <Typography.Text type="secondary" style={{ fontSize: 12 }}>
                Nothing here yet — drag an address onto this box to make it the company's
                rather than anyone's.
              </Typography.Text>
            ) : (
              <List
                size="small"
                dataSource={companyWide}
                renderItem={(ct) => (
                  <DraggableContact
                    ct={ct}
                    editing={editMode}
                    onEdit={onEditContact}
                    onDelete={(target) => removeContact.mutate(target.id)}
                  />
                )}
              />
            )}
          </DropZone>
        )}

        {!people || people.length === 0 ? (
          <Typography.Text type="secondary">No people.</Typography.Text>
        ) : (
          <Collapse
            activeKey={openKeys}
            // Not an accordion any more: "expand all" is the point, and one person at a
            // time cannot honour it.
            onChange={(key) => {
              const next = (Array.isArray(key) ? key : [key]).map(String);
              // Only a newly opened panel is a person being looked at; a collapse is not.
              const opened = next.find((k) => !openKeys.includes(k));
              setOpenKeys(next);
              const p = people.find((x) => String(x.id) === opened);
              if (p) {
                recordRecent({
                  kind: 'person',
                  id: p.id,
                  title: p.fullName,
                  subtitle: p.companyName,
                  companyId: p.companyId,
                });
              }
            }}
            items={people.map((p) => {
              const personContacts = byPerson.get(p.id) ?? [];
              return {
                key: String(p.id),
                label: (
                  <DropZone personId={p.id} active={dragging != null}>
                    <Space wrap>
                      <strong>{p.fullName}</strong>
                      {p.hasLeft && (
                        <Tooltip title="No longer works here. Every address and number of theirs is out of circulations — left out of collection, and skipped at send time even when already on a list.">
                          <Tag color="red">left</Tag>
                        </Tooltip>
                      )}
                      <GreetingName title={p.title} name={p.greetingName} type="success" />
                      <Typography.Text type="secondary">
                        {personContacts.length} contact{personContacts.length === 1 ? '' : 's'}
                      </Typography.Text>
                      {editMode && (
                        // The label is the Collapse's own toggle, and the confirm popup is
                        // a React child of this span — without stopping propagation here,
                        // both the buttons and their confirmations would toggle the panel.
                        <span onClick={(e) => e.stopPropagation()}>
                          <Space size={4}>
                            <Tooltip title="Edit person">
                              <Button
                                size="small"
                                aria-label={`Edit ${p.fullName}`}
                                icon={<EditOutlined />}
                                onClick={() => onEditPerson(p)}
                              />
                            </Tooltip>
                            <Tooltip title="Add contact for this person">
                              <Button
                                size="small"
                                aria-label={`Add contact for ${p.fullName}`}
                                icon={<PlusOutlined />}
                                onClick={() => onAddContact(p.id)}
                              />
                            </Tooltip>
                            <LeftCompanyButton p={p} />
                            <Popconfirm
                              title="Delete this person?"
                              onConfirm={() => removePerson.mutate(p.id)}
                            >
                              <Button
                                size="small"
                                danger
                                aria-label={`Delete ${p.fullName}`}
                                icon={<DeleteOutlined />}
                              />
                            </Popconfirm>
                          </Space>
                        </span>
                      )}
                    </Space>
                  </DropZone>
                ),
                children: (
                  // A second target for the same person, so an open panel accepts a drop
                  // anywhere in it and not only on its header.
                  <DropZone personId={p.id} active={dragging != null} suffix="body">
                    {personContacts.length ? (
                      <List
                        size="small"
                        dataSource={personContacts}
                        renderItem={(ct) => (
                          <DraggableContact
                            ct={ct}
                            editing={editMode}
                            showGreeting={false}
                            onEdit={onEditContact}
                            onDelete={(target) => removeContact.mutate(target.id)}
                          />
                        )}
                      />
                    ) : (
                      <Typography.Text type="secondary">
                        {dragging ? 'Drop here to file it under this person.' : 'No contacts'}
                      </Typography.Text>
                    )}
                  </DropZone>
                ),
              };
            })}
          />
        )}
      </ContactRowExpansion>

      {/* Drawn by us rather than by the browser, so the row being carried is legible over
          the drawer instead of a translucent screenshot of half the list. */}
      <DragOverlay>
        {dragging && (
          <Tag color="blue" style={{ boxShadow: '0 2px 8px rgba(0,0,0,.15)' }}>
            {dragging.contactValue}
          </Tag>
        )}
      </DragOverlay>
    </DndContext>
  );
}

/**
 * One group that accepts a dropped contact. `personId` undefined means the company itself.
 *
 * Highlighted only while a drag is actually in flight — a list that outlines its groups all
 * the time is a list shouting about a feature nobody is using yet.
 */
function DropZone({
  personId,
  active,
  suffix = 'head',
  style,
  children,
}: {
  personId: number | undefined;
  active: boolean;
  /** Distinguishes a person's header target from their body target; ids must be unique. */
  suffix?: string;
  style?: CSSProperties;
  children: ReactNode;
}) {
  const { setNodeRef, isOver } = useDroppable({
    id: `${personId ?? 'company'}:${suffix}`,
    data: { personId },
  });
  return (
    <div
      ref={setNodeRef}
      style={{
        borderRadius: 6,
        transition: 'background .15s, outline-color .15s',
        outline: active ? '1px dashed rgba(0,0,0,.15)' : '1px dashed transparent',
        background: isOver ? 'rgba(22,119,255,.10)' : undefined,
        ...style,
      }}
    >
      {children}
    </div>
  );
}

/** A contact row with a grip, so it can be carried to another group. */
function DraggableContact({
  ct,
  editing,
  showGreeting,
  onEdit,
  onDelete,
}: {
  ct: ContactResponse;
  editing: boolean;
  showGreeting?: boolean;
  onEdit: (ct: ContactResponse) => void;
  onDelete: (ct: ContactResponse) => void;
}) {
  const { attributes, listeners, setNodeRef } = useDraggable({
    id: `contact:${ct.id}`,
    data: { ct },
    disabled: !editing,
  });
  return (
    <div ref={setNodeRef}>
      <ContactLine
        ct={ct}
        editing={editing}
        showGreeting={showGreeting}
        dragHandle={editing ? <ContactDragHandle {...listeners} {...attributes} /> : undefined}
        onEdit={onEdit}
        onDelete={onDelete}
      />
    </div>
  );
}
